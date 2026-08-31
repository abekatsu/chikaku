pub mod devices;
pub mod families;
pub mod location;

use serde::{Deserialize, Serialize};

/// 端末設定のうち、見守りの成否を左右するもの (Issue #4)。
///
/// 端末が位置情報と一緒に報告し (`POST /api/v1/location`)、
/// ダッシュボードが `GET .../latest` で読む。送受で同じ形なので 1 つで持つ。
#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
pub struct DeviceHealth {
    /// 電池の最適化から除外されているか。false だと送信が数十分遅れる。
    pub battery_unrestricted: bool,
    /// 常駐通知を表示できるか。false だと親が動作を確認できない。
    pub notifications_enabled: bool,
    /// 位置情報が「常に許可」か。false だと画面を消した間の測位が止まる。
    pub background_location: bool,
}

/// 1 回の測位がどこから来たか (Issue #13)。
///
/// **精度では区別できない誤りを見分けるために持つ。** Wi-Fi / 基地局測位は
/// データベース上の登録位置を返すもので、AP が移動していれば大きく外れる。
/// 実際、100m を自称しながら 7km 外した測位が 10 件記録された。
///
/// `kind` の判定は端末側の経験則なので、**根拠になった生の値も一緒に持つ。**
/// 判定を見直したくなったときに、過去のデータから引き直せるようにしてある。
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocationSource {
    /// `satellite` / `network` / `unknown`
    pub kind: String,
    pub provider: Option<String>,
    pub has_altitude: bool,
    pub has_speed: bool,
    pub has_bearing: bool,
}
