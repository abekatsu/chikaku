import L from "leaflet";
import { useEffect, useRef } from "react";

import type { DeviceLatest, HistoryEvent } from "../api/types";
import { freshness, relativeTime } from "../lib/format";
import { isTrustedFix, trustedHistory } from "../lib/trust";

/** 端末が1つも位置を持たないときの初期表示（東京駅）。 */
const FALLBACK_CENTER: L.LatLngExpression = [35.6812, 139.7671];
const FALLBACK_ZOOM = 12;
const FOCUS_ZOOM = 16;

interface MapViewProps {
  devices: DeviceLatest[];
  selectedDeviceId: string | null;
  onSelectDevice: (deviceId: string) => void;
  /** 履歴表示中は経路を重ねる。null なら現在地のみ。 */
  history: HistoryEvent[] | null;
  /** 値が変わるたびに選択中の端末へ寄せ直す。 */
  focusNonce: number;
}

/**
 * 端末ごとのピン。Leaflet 既定のマーカー画像はバンドラを通すと
 * URL が壊れるため、DivIcon で自前に描く。
 * 色は情報の鮮度を表す（古い位置を新しいものと同じ見た目にしない）。
 */
function pinIcon(device: DeviceLatest, selected: boolean): L.DivIcon {
  const state = freshness(device.latest?.recorded_at ?? null);
  const initial = [...device.device_name][0] ?? "親";
  return L.divIcon({
    className: "pin-wrapper",
    html: `<div class="pin pin--${state}${selected ? " pin--selected" : ""}">
             <span class="pin__label">${escapeHtml(initial)}</span>
           </div>`,
    iconSize: [36, 36],
    iconAnchor: [18, 18],
  });
}

function escapeHtml(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c] ?? c,
  );
}

export function MapView({
  devices,
  selectedDeviceId,
  onSelectDevice,
  history,
  focusNonce,
}: MapViewProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const mapRef = useRef<L.Map | null>(null);
  const markerLayerRef = useRef<L.LayerGroup | null>(null);
  const historyLayerRef = useRef<L.LayerGroup | null>(null);
  // 最初のデータが来たときだけ全体が入るように寄せる。
  // 以降は利用者の操作を上書きしない。
  const didInitialFitRef = useRef(false);
  const onSelectRef = useRef(onSelectDevice);
  onSelectRef.current = onSelectDevice;

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    const map = L.map(containerRef.current, {
      center: FALLBACK_CENTER,
      zoom: FALLBACK_ZOOM,
      zoomControl: true,
    });

    L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
      maxZoom: 19,
      // OpenStreetMap の利用規約で表示が必須。
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    }).addTo(map);

    markerLayerRef.current = L.layerGroup().addTo(map);
    historyLayerRef.current = L.layerGroup().addTo(map);
    mapRef.current = map;

    // Leaflet はコンテナの寸法をキャッシュしており、自分ではリサイズに気づかない。
    // 画面の回転や幅の変更でレイアウトが縦積みに切り替わると、
    // 古い寸法のまま描画して表示位置がずれる（ピンが画面外に出る）。
    const observer = new ResizeObserver(() => {
      // 中心を保ったまま寸法だけ取り直す。
      map.invalidateSize({ animate: false });
    });
    observer.observe(containerRef.current);

    return () => {
      observer.disconnect();
      map.remove();
      mapRef.current = null;
    };
  }, []);

  // 現在地のピンを描き直す
  useEffect(() => {
    const map = mapRef.current;
    const layer = markerLayerRef.current;
    if (!map || !layer) return;

    layer.clearLayers();
    const points: L.LatLngExpression[] = [];

    for (const device of devices) {
      const fix = device.latest;
      if (!fix) continue;
      const position: L.LatLngExpression = [fix.lat, fix.lng];
      points.push(position);

      // 測位誤差を円で示す。点だけ出すと精度を過大に見せてしまう。
      // **疑わしい測位は破線と灰色にする（Issue #13, #16）。** Wi-Fi や基地局からの
      // 推定は 100m を自称して数 km 外すことがあり、実線で描くと嘘の確信を与える。
      // 最新位置には前後の点が無いので、スパイク判定はここでは効かない。
      const trusted = isTrustedFix(fix);
      L.circle(position, {
        radius: fix.accuracy,
        color: trusted ? "#1B5E8C" : "#8A8A8A",
        weight: 1,
        opacity: trusted ? 0.35 : 0.5,
        dashArray: trusted ? undefined : "4 4",
        fillColor: trusted ? "#1B5E8C" : "#8A8A8A",
        fillOpacity: 0.08,
      }).addTo(layer);

      L.marker(position, {
        icon: pinIcon(device, device.device_id === selectedDeviceId),
        title: device.device_name,
        keyboard: true,
        alt: `${device.device_name} の現在地`,
      })
        .bindTooltip(
          `<strong>${escapeHtml(device.device_name)}</strong><br>${relativeTime(fix.recorded_at)}` +
            (trusted ? "" : "<br><em>おおよその位置</em>"),
          { direction: "top", offset: [0, -18] },
        )
        .on("click", () => onSelectRef.current(device.device_id))
        .addTo(layer);
    }

    if (!didInitialFitRef.current && points.length > 0) {
      didInitialFitRef.current = true;
      if (points.length === 1) {
        map.setView(points[0] as L.LatLngExpression, FOCUS_ZOOM);
      } else {
        map.fitBounds(L.latLngBounds(points).pad(0.2));
      }
    }
  }, [devices, selectedDeviceId]);

  // 履歴の経路
  useEffect(() => {
    const layer = historyLayerRef.current;
    if (!layer) return;
    layer.clearLayers();
    if (!history || history.length === 0) return;

    const points: L.LatLngExpression[] = history.map((e) => [e.lat, e.lng]);

    // **経路を信頼度で描き分ける（Issue #13, #16）。** 疑わしい点を消してしまうと
    // 「病院にいた」まで消える。消さずに、確からしくないことを線で伝える。
    // 隣接する 2 点のどちらかが疑わしければ、その区間を破線にする。
    // 前後の点を見るスパイク判定があるため、点ごとの判定は先にまとめて行う。
    const trustedPoints = trustedHistory(history);
    for (let i = 0; i < history.length - 1; i += 1) {
      const a = history[i];
      const b = history[i + 1];
      if (!a || !b) continue;
      const trusted = trustedPoints[i] === true && trustedPoints[i + 1] === true;
      L.polyline(
        [
          [a.lat, a.lng],
          [b.lat, b.lng],
        ],
        {
          color: trusted ? "#2E7D32" : "#9E9E9E",
          weight: trusted ? 4 : 3,
          opacity: trusted ? 0.75 : 0.6,
          dashArray: trusted ? undefined : "6 6",
        },
      ).addTo(layer);
    }

    // 経路の始点と終点だけ小さく示す。中間点まで出すと地図が埋まる。
    const first = history[0];
    const last = history[history.length - 1];
    if (first) {
      L.circleMarker([first.lat, first.lng], {
        radius: 5,
        color: "#2E7D32",
        fillColor: "#ffffff",
        fillOpacity: 1,
        weight: 2,
      })
        .bindTooltip("開始", { direction: "top" })
        .addTo(layer);
    }
    if (last && last !== first) {
      L.circleMarker([last.lat, last.lng], {
        radius: 6,
        color: "#2E7D32",
        fillColor: "#2E7D32",
        fillOpacity: 1,
        weight: 2,
      })
        .bindTooltip("終了", { direction: "top" })
        .addTo(layer);
    }

    mapRef.current?.fitBounds(L.latLngBounds(points).pad(0.2));
  }, [history]);

  // 「地図で見る」を押されたら選択中の端末へ寄せる
  useEffect(() => {
    if (focusNonce === 0) return;
    const map = mapRef.current;
    const fix = devices.find((d) => d.device_id === selectedDeviceId)?.latest;
    if (map && fix) {
      map.setView([fix.lat, fix.lng], Math.max(map.getZoom(), FOCUS_ZOOM), {
        animate: true,
      });
    }
    // focusNonce の変化だけを合図にする。devices の更新では動かさない。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [focusNonce]);

  return <div className="map" ref={containerRef} role="application" aria-label="現在地の地図" />;
}
