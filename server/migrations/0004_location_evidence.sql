-- 測位の出どころの判断材料を入れ替える (Issue #16)
--
-- 0003 で記録を始めた「高度・速度・方位の有無」は、実機ではどれも出どころを
-- 区別しなかった。本番 1,770 件のうち `network` と判定できたのは 4 件だけで、
-- 33km 外した点も `satellite` になっていた。
--
-- 原因は前提の誤り。実機の `dumpsys location` を見ると、network プロバイダが
-- `supports=[altitude]` を宣言し、高度・鉛直精度・標高をすべて返してくる。
-- 「Wi-Fi / 基地局測位は原理的に高度を出せない」という 0003 の前提が、
-- この端末では成り立っていなかった。
--
-- そこで候補を入れ替える。gps にしか付いていなかったのは速度・方位の「精度」と
-- extras の衛星情報だったので、そこを狙う。ただしプロバイダの出力に付いていても
-- 融合を通ってアプリに届くとは限らないため、**判定はまだ作らない。**
-- 実機のデータを見てから決める（0003 で同じ順序を誤った）。
--
-- 全て NULL 可。**NULL は「無い」ではなく「報告が無い」。**
-- 0004 より前の全行、古いアプリ、iOS 版、および API 34 未満の端末
-- （`source_has_msl_altitude` のみ）が該当する。
ALTER TABLE location_events ADD COLUMN source_has_vertical_accuracy INTEGER;
ALTER TABLE location_events ADD COLUMN source_has_speed_accuracy INTEGER;
ALTER TABLE location_events ADD COLUMN source_has_bearing_accuracy INTEGER;
ALTER TABLE location_events ADD COLUMN source_has_msl_altitude INTEGER;
-- 測位されてから端末のキューに入るまでの経過時間（ミリ秒）。
-- 起動直後に返るキャッシュ済みの古い位置を見分けるため。
ALTER TABLE location_events ADD COLUMN source_age_millis INTEGER;
-- extras のキー名のみを連結したもの。値は入れない（端末依存で何が来るか読めない）。
ALTER TABLE location_events ADD COLUMN source_extra_keys TEXT;
-- extras の satellites。融合結果に残っていれば、これが決定打になりうる。
ALTER TABLE location_events ADD COLUMN source_satellites INTEGER;
