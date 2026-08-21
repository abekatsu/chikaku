//! API の結合テスト。
//!
//! Android 側 (`ApiClient.kt`) が前提にしている契約
//! ―― ステータスコードの意味と JSON の形 ―― をここで固定する。

use axum::Router;
use axum::body::Body;
use axum::http::{Request, StatusCode, header};
use chikaku_server::{
    bootstrap, clock, config::Config, db::Db, retention, routes, state::AppState,
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use std::time::Duration;
use tower::ServiceExt;

struct TestApp {
    router: Router,
    db: Db,
    dir: std::path::PathBuf,
}

impl Drop for TestApp {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

impl TestApp {
    /// テストごとに使い捨てのファイル DB を作る。
    /// `:memory:` はプール内の接続ごとに別々の DB になってしまうため使わない。
    async fn new() -> Self {
        let dir = std::env::temp_dir().join(format!("chikaku-test-{}", unique_name()));
        std::fs::create_dir_all(&dir).unwrap();
        let url = format!("sqlite://{}/test.db", dir.display());

        let db = chikaku_server::db::connect(&url).await.unwrap();
        let config = Config {
            database_url: url,
            bind: "127.0.0.1:0".parse().unwrap(),
            retention: Duration::from_secs(90 * 86_400),
            session_ttl: Duration::from_secs(3_600),
            invite_ttl: Duration::from_secs(600),
            cors_origins: vec![],
        };
        let router = routes::router(AppState::new(db.clone(), config));
        Self { router, db, dir }
    }

    async fn call(&self, req: Request<Body>) -> (StatusCode, Value) {
        let res = self.router.clone().oneshot(req).await.unwrap();
        let status = res.status();
        let bytes = res.into_body().collect().await.unwrap().to_bytes();
        let body = if bytes.is_empty() {
            Value::Null
        } else {
            serde_json::from_slice(&bytes).unwrap_or(Value::Null)
        };
        (status, body)
    }

    async fn post(&self, path: &str, token: Option<&str>, body: Value) -> (StatusCode, Value) {
        self.call(build(http::Method::POST, path, token, Some(body)))
            .await
    }

    async fn get(&self, path: &str, token: Option<&str>) -> (StatusCode, Value) {
        self.call(build(http::Method::GET, path, token, None)).await
    }

    /// 家族と子アカウントを作り、ログイン済みのセッショントークンを返す。
    async fn family_with_login(&self, email: &str) -> (String, String) {
        let created = bootstrap::create_family(&self.db, "テスト家族", email, "子", "password1234")
            .await
            .unwrap();
        let (status, body) = self
            .post(
                "/api/v1/auth/login",
                None,
                json!({"email": email, "password": "password1234"}),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "{body}");
        (
            created.family_id,
            body["token"].as_str().unwrap().to_owned(),
        )
    }

    /// 招待コードを発行して端末を登録し、device_token を返す。
    async fn paired_device(&self, family_id: &str, session: &str) -> (String, String) {
        let (status, invite) = self
            .post(
                &format!("/api/v1/families/{family_id}/invites"),
                Some(session),
                json!({}),
            )
            .await;
        assert_eq!(status, StatusCode::CREATED, "{invite}");

        let (status, body) = self
            .post(
                "/api/v1/devices/register",
                None,
                json!({
                    "invite_code": invite["code"],
                    "device_name": "お父さんのスマホ",
                    "device_model": "Pixel 9",
                }),
            )
            .await;
        assert_eq!(status, StatusCode::OK, "{body}");
        (
            body["device_id"].as_str().unwrap().to_owned(),
            body["device_token"].as_str().unwrap().to_owned(),
        )
    }
}

fn build(
    method: http::Method,
    path: &str,
    token: Option<&str>,
    body: Option<Value>,
) -> Request<Body> {
    let mut req = Request::builder().method(method).uri(path);
    if let Some(t) = token {
        req = req.header(header::AUTHORIZATION, format!("Bearer {t}"));
    }
    match body {
        Some(b) => req
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(b.to_string()))
            .unwrap(),
        None => req.body(Body::empty()).unwrap(),
    }
}

/// テストは並列に走るので、時刻だけでは名前が衝突する。
/// プロセス ID・ナノ秒・通し番号を重ねて一意にする。
fn unique_name() -> String {
    static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    format!("{}-{nanos}-{n}", std::process::id())
}

fn fix(device_id: &str, at: &str, lat: f64, lng: f64) -> Value {
    json!({
        "device_id": device_id,
        "lat": lat,
        "lng": lng,
        "accuracy": 12.5,
        "timestamp": at,
        "battery_level": 77,
    })
}

// ------------------------------------------------------------ ペアリング

#[tokio::test]
async fn pairing_flow_registers_a_device() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("a@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;

    assert!(!device_id.is_empty());
    // 端末トークンは 32 バイト hex。
    assert_eq!(token.len(), 64);
}

#[tokio::test]
async fn invite_code_can_only_be_used_once() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("b@example.com").await;

    let (_, invite) = app
        .post(
            &format!("/api/v1/families/{family_id}/invites"),
            Some(&session),
            json!({}),
        )
        .await;
    let code = invite["code"].as_str().unwrap().to_owned();

    let register = json!({"invite_code": code, "device_name": "端末", "device_model": "X"});
    let (first, _) = app
        .post("/api/v1/devices/register", None, register.clone())
        .await;
    assert_eq!(first, StatusCode::OK);

    // 2 回目は 401。アプリはこれを受けて「招待コードが正しくないか、
    // 期限が切れています」と表示する (MainViewModel の Unauthorized 分岐)。
    let (second, body) = app.post("/api/v1/devices/register", None, register).await;
    assert_eq!(second, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"], "invalid_invite_code");
}

#[tokio::test]
async fn invite_code_is_accepted_in_lowercase_and_with_hyphens() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("c@example.com").await;

    let (_, invite) = app
        .post(
            &format!("/api/v1/families/{family_id}/invites"),
            Some(&session),
            json!({}),
        )
        .await;
    let code = invite["code"].as_str().unwrap().to_lowercase();
    let typed = format!("{}-{}", &code[..4], &code[4..]);

    let (status, body) = app
        .post(
            "/api/v1/devices/register",
            None,
            json!({"invite_code": typed, "device_name": "端末", "device_model": "X"}),
        )
        .await;
    assert_eq!(status, StatusCode::OK, "{body}");
}

#[tokio::test]
async fn unknown_invite_code_is_rejected() {
    let app = TestApp::new().await;
    let (status, body) = app
        .post(
            "/api/v1/devices/register",
            None,
            json!({"invite_code": "XXXXXXXX", "device_name": "端末", "device_model": "X"}),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["error"], "invalid_invite_code");
}

#[tokio::test]
async fn device_name_is_required() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("d@example.com").await;
    let (_, invite) = app
        .post(
            &format!("/api/v1/families/{family_id}/invites"),
            Some(&session),
            json!({}),
        )
        .await;

    let (status, body) = app
        .post(
            "/api/v1/devices/register",
            None,
            json!({"invite_code": invite["code"], "device_name": "   ", "device_model": "X"}),
        )
        .await;
    // 4xx かつ message が日本語であること。アプリはこの文言をそのまま画面に出す。
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert!(body["message"].as_str().unwrap().contains("名前"), "{body}");
}

// ------------------------------------------------------------ 位置情報送信

#[tokio::test]
async fn location_is_stored_and_visible_in_latest() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("e@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;

    let at = clock::to_rfc3339(clock::now());
    let (status, body) = app
        .post(
            "/api/v1/location",
            Some(&token),
            fix(&device_id, &at, 35.681, 139.767),
        )
        .await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
    assert_eq!(body["stored"], true);

    let (status, body) = app
        .get(
            &format!("/api/v1/families/{family_id}/latest"),
            Some(&session),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    let device = &body["devices"][0];
    assert_eq!(device["device_id"], device_id.as_str());
    assert_eq!(device["device_name"], "お父さんのスマホ");
    assert_eq!(device["latest"]["lat"], 35.681);
    assert_eq!(device["latest"]["battery_level"], 77);
    assert_eq!(device["latest"]["recorded_at"], at.as_str());
}

#[tokio::test]
async fn resending_the_same_fix_does_not_duplicate() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("f@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;
    let at = clock::to_rfc3339(clock::now());
    let payload = fix(&device_id, &at, 35.0, 139.0);

    let (_, first) = app
        .post("/api/v1/location", Some(&token), payload.clone())
        .await;
    assert_eq!(first["stored"], true);

    // 応答が失われたあとの WorkManager 再送を模す。
    let (status, second) = app.post("/api/v1/location", Some(&token), payload).await;
    assert_eq!(status, StatusCode::ACCEPTED);
    assert_eq!(second["stored"], false, "重複が畳まれていない");

    let count: (i64,) = sqlx::query_as("SELECT count(*) FROM location_events")
        .fetch_one(&app.db)
        .await
        .unwrap();
    assert_eq!(count.0, 1);
}

#[tokio::test]
async fn a_device_cannot_post_as_another_device() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("g@example.com").await;
    let (_, token) = app.paired_device(&family_id, &session).await;

    let at = clock::to_rfc3339(clock::now());
    let (status, _) = app
        .post(
            "/api/v1/location",
            Some(&token),
            fix("someone-else", &at, 35.0, 139.0),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn location_requires_a_valid_token() {
    let app = TestApp::new().await;
    let at = clock::to_rfc3339(clock::now());

    for token in [None, Some("not-a-real-token")] {
        let (status, _) = app
            .post("/api/v1/location", token, fix("d", &at, 35.0, 139.0))
            .await;
        assert_eq!(status, StatusCode::UNAUTHORIZED, "token = {token:?}");
    }
}

#[tokio::test]
async fn malformed_payloads_are_client_errors_not_retried_forever() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("h@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;
    let now = clock::now();

    let cases = [
        ("壊れた時刻", fix(&device_id, "きのう", 35.0, 139.0)),
        (
            "未来すぎる時刻",
            fix(
                &device_id,
                &clock::to_rfc3339(now + 60 * 86_400_000),
                35.0,
                139.0,
            ),
        ),
        (
            "保持期間より古い",
            fix(
                &device_id,
                &clock::to_rfc3339(now - 200 * 86_400_000),
                35.0,
                139.0,
            ),
        ),
        (
            "緯度が範囲外",
            fix(&device_id, &clock::to_rfc3339(now), 91.0, 139.0),
        ),
        (
            "経度が範囲外",
            fix(&device_id, &clock::to_rfc3339(now), 35.0, 181.0),
        ),
    ];

    for (name, payload) in cases {
        let (status, body) = app.post("/api/v1/location", Some(&token), payload).await;
        // 4xx かつ 401/408/429 以外 ―― アプリはこれを ClientError として
        // 扱い、再試行を打ち切ってキューから捨てる。
        assert_eq!(status, StatusCode::BAD_REQUEST, "{name}: {body}");
        assert!(body["message"].is_string(), "{name}: message が無い");
    }
}

#[tokio::test]
async fn battery_level_minus_one_is_accepted() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("i@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;

    // 端末は電池残量を取れないとき -1 を送る (LocationRepository)。
    let mut payload = fix(&device_id, &clock::to_rfc3339(clock::now()), 35.0, 139.0);
    payload["battery_level"] = json!(-1);

    let (status, body) = app.post("/api/v1/location", Some(&token), payload).await;
    assert_eq!(status, StatusCode::ACCEPTED, "{body}");
}

#[tokio::test]
async fn revoked_device_can_no_longer_send() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("j@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;

    let (status, _) = app
        .post(
            &format!("/api/v1/families/{family_id}/devices/{device_id}/revoke"),
            Some(&session),
            json!({}),
        )
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    let at = clock::to_rfc3339(clock::now());
    let (status, _) = app
        .post(
            "/api/v1/location",
            Some(&token),
            fix(&device_id, &at, 35.0, 139.0),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

// ------------------------------------------------------------ ダッシュボード

#[tokio::test]
async fn login_rejects_wrong_credentials_without_revealing_which() {
    let app = TestApp::new().await;
    app.family_with_login("k@example.com").await;

    let unknown = app
        .post(
            "/api/v1/auth/login",
            None,
            json!({"email": "nobody@example.com", "password": "password1234"}),
        )
        .await;
    let wrong_password = app
        .post(
            "/api/v1/auth/login",
            None,
            json!({"email": "k@example.com", "password": "wrong-password"}),
        )
        .await;

    assert_eq!(unknown.0, StatusCode::UNAUTHORIZED);
    assert_eq!(wrong_password.0, StatusCode::UNAUTHORIZED);
    // 存在しないアカウントとパスワード誤りで応答が変わらないこと。
    assert_eq!(unknown.1, wrong_password.1);
}

#[tokio::test]
async fn history_filters_by_time_range() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("l@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;
    let now = clock::now();

    for minutes_ago in [90i64, 30, 5] {
        let at = clock::to_rfc3339(now - minutes_ago * 60_000);
        let (status, _) = app
            .post(
                "/api/v1/location",
                Some(&token),
                fix(&device_id, &at, 35.0, 139.0),
            )
            .await;
        assert_eq!(status, StatusCode::ACCEPTED);
    }

    let from = clock::to_rfc3339(now - 60 * 60_000);
    let (status, body) = app
        .get(
            &format!("/api/v1/families/{family_id}/history?from={from}"),
            Some(&session),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    // 90 分前の 1 件だけが範囲外。
    assert_eq!(body["events"].as_array().unwrap().len(), 2, "{body}");
    assert_eq!(body["truncated"], false);

    // 昇順であること。
    let events = body["events"].as_array().unwrap();
    assert!(events[0]["recorded_at"].as_str() < events[1]["recorded_at"].as_str());
}

#[tokio::test]
async fn history_rejects_malformed_time_bounds() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("m@example.com").await;

    let (status, body) = app
        .get(
            &format!("/api/v1/families/{family_id}/history?from=yesterday"),
            Some(&session),
        )
        .await;
    assert_eq!(status, StatusCode::BAD_REQUEST, "{body}");
}

#[tokio::test]
async fn history_limit_reports_truncation() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("n@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;
    let now = clock::now();

    for i in 0..3i64 {
        let at = clock::to_rfc3339(now - i * 60_000);
        app.post(
            "/api/v1/location",
            Some(&token),
            fix(&device_id, &at, 35.0, 139.0),
        )
        .await;
    }

    let (status, body) = app
        .get(
            &format!("/api/v1/families/{family_id}/history?limit=2"),
            Some(&session),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["events"].as_array().unwrap().len(), 2);
    assert_eq!(body["truncated"], true);
}

// ------------------------------------------------------------ 家族の分離

#[tokio::test]
async fn a_family_cannot_reach_another_familys_data() {
    let app = TestApp::new().await;
    let (family_a, session_a) = app.family_with_login("o@example.com").await;
    let (family_b, session_b) = app.family_with_login("p@example.com").await;

    let (device_id, token) = app.paired_device(&family_a, &session_a).await;
    let at = clock::to_rfc3339(clock::now());
    app.post(
        "/api/v1/location",
        Some(&token),
        fix(&device_id, &at, 35.0, 139.0),
    )
    .await;

    // B のセッションで A の family_id を指す。
    // 「存在するが権限が無い」ことすら漏らさないよう 404 を返す。
    for path in [
        format!("/api/v1/families/{family_a}/latest"),
        format!("/api/v1/families/{family_a}/history"),
    ] {
        let (status, _) = app.get(&path, Some(&session_b)).await;
        assert_eq!(status, StatusCode::NOT_FOUND, "{path}");
    }

    let (status, _) = app
        .post(
            &format!("/api/v1/families/{family_a}/invites"),
            Some(&session_b),
            json!({}),
        )
        .await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    let (status, _) = app
        .post(
            &format!("/api/v1/families/{family_a}/devices/{device_id}/revoke"),
            Some(&session_b),
            json!({}),
        )
        .await;
    assert_eq!(status, StatusCode::NOT_FOUND);

    // B は自分の家族なら見られる（端末はまだ 0 台）。
    let (status, body) = app
        .get(
            &format!("/api/v1/families/{family_b}/latest"),
            Some(&session_b),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["devices"].as_array().unwrap().len(), 0);
}

#[tokio::test]
async fn dashboard_endpoints_reject_a_device_token() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("q@example.com").await;
    let (_, device_token) = app.paired_device(&family_id, &session).await;

    // 端末トークンでダッシュボード API を叩けないこと。
    let (status, _) = app
        .get(
            &format!("/api/v1/families/{family_id}/latest"),
            Some(&device_token),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn logout_invalidates_only_that_session() {
    let app = TestApp::new().await;
    let (family_id, first) = app.family_with_login("r@example.com").await;
    let (_, second) = app
        .post(
            "/api/v1/auth/login",
            None,
            json!({"email": "r@example.com", "password": "password1234"}),
        )
        .await;
    let second = second["token"].as_str().unwrap().to_owned();

    let (status, _) = app
        .post("/api/v1/auth/logout", Some(&first), json!({}))
        .await;
    assert_eq!(status, StatusCode::NO_CONTENT);

    let (status, _) = app
        .get(
            &format!("/api/v1/families/{family_id}/latest"),
            Some(&first),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    // もう一方の端末でのログインは生きている。
    let (status, _) = app
        .get(
            &format!("/api/v1/families/{family_id}/latest"),
            Some(&second),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
}

// ------------------------------------------------------------ 保持期間

#[tokio::test]
async fn sweep_deletes_history_past_the_retention_window() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("s@example.com").await;
    let (device_id, token) = app.paired_device(&family_id, &session).await;

    let at = clock::to_rfc3339(clock::now());
    app.post(
        "/api/v1/location",
        Some(&token),
        fix(&device_id, &at, 35.0, 139.0),
    )
    .await;

    // 保持期間を 0 にして掃除すると、今入れた分も対象になる。
    let deleted = retention::sweep(&app.db, Duration::from_secs(0))
        .await
        .unwrap();
    assert_eq!(deleted, 1);

    let (status, body) = app
        .get(
            &format!("/api/v1/families/{family_id}/latest"),
            Some(&session),
        )
        .await;
    assert_eq!(status, StatusCode::OK);
    // 端末は残り、位置だけが消える。
    assert_eq!(body["devices"].as_array().unwrap().len(), 1);
    assert!(body["devices"][0]["latest"].is_null(), "{body}");
}

#[tokio::test]
async fn expired_session_is_rejected() {
    let app = TestApp::new().await;
    let (family_id, session) = app.family_with_login("t@example.com").await;

    sqlx::query("UPDATE child_sessions SET expires_at = ?1")
        .bind(clock::now() - 1)
        .execute(&app.db)
        .await
        .unwrap();

    let (status, _) = app
        .get(
            &format!("/api/v1/families/{family_id}/latest"),
            Some(&session),
        )
        .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

// ------------------------------------------------------------ その他

#[tokio::test]
async fn unknown_paths_return_json_errors() {
    let app = TestApp::new().await;
    let (status, body) = app.get("/api/v1/nope", None).await;
    assert_eq!(status, StatusCode::NOT_FOUND);
    // アプリのエラー解釈は常に {error, message} を前提にしている。
    assert!(body["error"].is_string(), "{body}");
    assert!(body["message"].is_string(), "{body}");
}

#[tokio::test]
async fn healthz_is_open() {
    let app = TestApp::new().await;
    let res = app
        .router
        .clone()
        .oneshot(
            Request::builder()
                .uri("/healthz")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
}
