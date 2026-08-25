import CoreLocation
import SwiftUI
import UserNotifications

/// 権限を段階に分けて取得する。Android 版と同じく一度にまとめて要求しない。
/// 「何のための許可か」を1つずつ説明してから進めるほうが、親が迷って
/// 拒否してしまう事故が減る。
struct PermissionView: View {

    @Bindable var model: AppModel
    @Environment(\.openURL) private var openURL

    @State private var notificationsGranted = false
    @State private var didRequestAlways = false

    private var tracker: LocationTracker { model.tracker }

    var body: some View {
        VStack(spacing: 0) {
            ScreenScaffold(title: S.permissionTitle) {
                // 手順の見出し番号は表示されるものだけで振り直す。
                // 条件で隠れる手順があるため、番号を固定にすると
                // 「1. の次が 4.」のような並びになって親を迷わせる。
                let steps = visibleSteps
                ForEach(Array(steps.enumerated()), id: \.element.id) { index, step in
                    stepView(number: index + 1, step: step)
                }

                if tracker.authorizationStatus == .denied || tracker.authorizationStatus == .restricted {
                    Text(S.permissionDenied)
                        .font(Theme.bodyFont)
                        .foregroundStyle(Theme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }

            PrimaryButton(
                title: S.permissionStart,
                enabled: tracker.hasAlwaysAuthorization && tracker.hasFullAccuracy
            ) {
                model.startTracking()
            }
            .padding(Theme.screenPadding)
        }
        .task { await refreshNotificationStatus() }
    }

    // MARK: - 手順の組み立て

    private struct Step: Identifiable {
        let id: String
        let title: String
        let body: String
        let done: Bool
        let actionTitle: String
        let action: () -> Void
    }

    private var visibleSteps: [Step] {
        var steps: [Step] = [
            Step(
                id: "whenInUse",
                title: S.permissionStepWhenInUseTitle,
                body: S.permissionStepWhenInUseBody,
                done: tracker.hasWhenInUseAuthorization,
                actionTitle: S.permissionGrant,
                action: { tracker.requestWhenInUseAuthorization() }
            )
        ]

        if tracker.hasWhenInUseAuthorization {
            steps.append(
                Step(
                    id: "always",
                    title: S.permissionStepAlwaysTitle,
                    body: S.permissionStepAlwaysBody,
                    done: tracker.hasAlwaysAuthorization,
                    // 一度 requestAlwaysAuthorization を出したあとは、iOS は
                    // 二度目のダイアログを出さない。設定画面へ送るしかなくなる。
                    actionTitle: didRequestAlways ? S.permissionOpenSettings : S.permissionGrant,
                    action: {
                        if didRequestAlways {
                            openSettings()
                        } else {
                            didRequestAlways = true
                            tracker.requestAlwaysAuthorization()
                        }
                    }
                )
            )
        }

        // 「おおよその位置」に落ちているときだけ出す。既に正確なら見せる意味がない。
        if tracker.hasWhenInUseAuthorization, !tracker.hasFullAccuracy {
            steps.append(
                Step(
                    id: "precise",
                    title: S.permissionStepPreciseTitle,
                    body: S.permissionStepPreciseBody,
                    done: false,
                    actionTitle: S.permissionOpenSettings,
                    action: openSettings
                )
            )
        }

        steps.append(
            Step(
                id: "notification",
                title: S.permissionStepNotificationTitle,
                body: S.permissionStepNotificationBody,
                done: notificationsGranted,
                actionTitle: S.permissionGrant,
                action: { Task { await requestNotifications() } }
            )
        )
        return steps
    }

    @ViewBuilder
    private func stepView(number: Int, step: Step) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: step.done ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 26))
                    .foregroundStyle(step.done ? Theme.ok : Color.secondary)
                Text("\(number). \(step.title)").font(Theme.sectionFont)
            }
            Text(step.body)
                .font(Theme.bodyFont)
                .fixedSize(horizontal: false, vertical: true)
            if step.done {
                Text(S.permissionGranted)
                    .font(Theme.captionFont)
                    .foregroundStyle(Theme.ok)
            } else {
                SecondaryButton(title: step.actionTitle, action: step.action)
            }
        }
        .padding(.bottom, 8)
    }

    // MARK: - 補助

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url)
    }

    private func requestNotifications() async {
        let center = UNUserNotificationCenter.current()
        let granted = (try? await center.requestAuthorization(options: [.alert, .sound])) ?? false
        notificationsGranted = granted
    }

    private func refreshNotificationStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        notificationsGranted = settings.authorizationStatus == .authorized
    }
}
