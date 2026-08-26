-- 端末設定の健康状態 (Issue #4)
--
-- 電池の最適化から除外されていない、通知が出せない、位置情報が「常に許可」でない。
-- どれも見守りを静かに壊すが、親の端末の画面にしか現れないため誰も気づけなかった。
-- 位置情報の送信に相乗りさせて子側のダッシュボードから見えるようにする。
--
-- 位置ではなく端末に紐づく情報なので location_events ではなくこちらに持ち、
-- 送信のたびに上書きする。履歴は残さない。「いまどうなっているか」が分かればよく、
-- 過去の設定を遡る用途は無いため。
--
-- NULL は「まだ報告を受けていない」を意味する。この列より前のアプリからは
-- 送られてこないので、「問題なし(1)」でも「問題あり(0)」でもない第三の状態が要る。
-- ここを 0 や 1 で埋めてしまうと、報告できていないだけの端末に警告が出る。
ALTER TABLE parent_devices ADD COLUMN battery_unrestricted INTEGER;
ALTER TABLE parent_devices ADD COLUMN notifications_enabled INTEGER;
ALTER TABLE parent_devices ADD COLUMN background_location INTEGER;
ALTER TABLE parent_devices ADD COLUMN health_reported_at INTEGER;
