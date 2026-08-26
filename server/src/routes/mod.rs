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
