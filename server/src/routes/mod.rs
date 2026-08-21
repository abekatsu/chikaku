pub mod auth;
pub mod devices;
pub mod families;
pub mod location;

use axum::Router;
use axum::http::{HeaderValue, Method, header};
use axum::routing::{get, post};
use tower_http::cors::{AllowOrigin, CorsLayer};
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::trace::TraceLayer;

use crate::error::AppError;
use crate::state::AppState;

/// 受け付ける最大リクエストサイズ。位置情報 1 件は数百バイトなので十分な余裕がある。
const MAX_BODY_BYTES: usize = 64 * 1024;

pub fn router(state: AppState) -> Router {
    let api = Router::new()
        .route("/devices/register", post(devices::register))
        .route("/location", post(location::create))
        .route("/auth/login", post(auth::login))
        .route("/auth/logout", post(auth::logout))
        .route("/auth/me", get(auth::me))
        .route("/families/{family_id}/latest", get(families::latest))
        .route("/families/{family_id}/history", get(families::history))
        .route(
            "/families/{family_id}/invites",
            post(families::create_invite),
        )
        .route(
            "/families/{family_id}/devices/{device_id}/revoke",
            post(families::revoke_device),
        );

    Router::new()
        .route("/healthz", get(healthz))
        .nest("/api/v1", api)
        // 未知のパスも JSON で返し、クライアントが常に同じ形の
        // エラー本文を読めるようにする。
        .fallback(|| async { AppError::NotFound })
        .layer(TraceLayer::new_for_http())
        .layer(RequestBodyLimitLayer::new(MAX_BODY_BYTES))
        .layer(cors(&state))
        .with_state(state)
}

async fn healthz() -> &'static str {
    "ok"
}

/// ダッシュボードは別オリジンで動くため CORS が要る。
/// 許可オリジンは設定で明示したものだけ。未設定なら層自体を無効にする。
fn cors(state: &AppState) -> CorsLayer {
    let origins: Vec<HeaderValue> = state
        .config
        .cors_origins
        .iter()
        .filter_map(|o| match HeaderValue::from_str(o) {
            Ok(v) => Some(v),
            Err(_) => {
                tracing::warn!(origin = %o, "CHIKAKU_CORS_ORIGINS に不正な値があるため無視します");
                None
            }
        })
        .collect();

    let layer = CorsLayer::new()
        .allow_methods([Method::GET, Method::POST, Method::OPTIONS])
        .allow_headers([header::AUTHORIZATION, header::CONTENT_TYPE]);

    if origins.is_empty() {
        layer
    } else {
        layer.allow_origin(AllowOrigin::list(origins))
    }
}
