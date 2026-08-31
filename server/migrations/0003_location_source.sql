-- 測位の出どころを記録する (Issue #13)
--
-- 訪問していない場所が 7km 離れた位置に 2 時間ぶん記録された。原因と考えられる
-- のは Wi-Fi / 基地局による測位で、これは「アクセスポイントや基地局が過去に
-- 観測された場所」を返すものであり、実測ではない。
--
-- **精度では区別できない。** 問題の測位は 100m を自称しながら 7km 外していた。
-- 誤っているときに限って誤差の申告が当てにならないため、閾値では選別できない。
--
-- 全て NULL 可。**NULL は「ネットワーク測位だった」ではなく「報告が無い」。**
-- この列より前のアプリと iOS 版は送ってこないため、第三の状態として区別する
-- （0003 より前の全行、および 0002 と同じ考え方）。
ALTER TABLE location_events ADD COLUMN source_kind TEXT;
ALTER TABLE location_events ADD COLUMN source_provider TEXT;
ALTER TABLE location_events ADD COLUMN source_has_altitude INTEGER;
ALTER TABLE location_events ADD COLUMN source_has_speed INTEGER;
ALTER TABLE location_events ADD COLUMN source_has_bearing INTEGER;
