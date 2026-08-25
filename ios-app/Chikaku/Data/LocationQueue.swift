import Foundation
import SwiftData

/// 送信待ちキューへの操作。Android の `PendingLocationDao` と対。
///
/// バックグラウンドの位置情報コールバックはメインスレッドに届くため、
/// ここも `@MainActor` に寄せて `mainContext` だけを触る。並行アクセスが
/// 起きない構造にしておくほうが、この規模では取り違えが起きにくい。
@MainActor
final class LocationQueue {

    private let container: ModelContainer
    private var context: ModelContext { container.mainContext }

    init(container: ModelContainer) {
        self.container = container
    }

    /// 保存先を明示して作る。既定の場所に任せないのは、バックアップ除外を
    /// 掛けるためにパスが必要だから（CLAUDE.md §5）。
    static func makeContainer(inMemory: Bool = false) throws -> ModelContainer {
        let configuration: ModelConfiguration
        if inMemory {
            configuration = ModelConfiguration(isStoredInMemoryOnly: true)
        } else {
            let directory = URL.applicationSupportDirectory
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appending(path: "chikaku-queue.store")
            configuration = ModelConfiguration(url: url)
        }
        let container = try ModelContainer(for: PendingLocation.self, configurations: configuration)
        if !inMemory {
            excludeFromBackup(configuration.url)
        }
        return container
    }

    /// 未送信の位置情報が iCloud バックアップへ流れ出るのを防ぐ。
    private static func excludeFromBackup(_ url: URL) {
        // SwiftData は本体に加えて -wal / -shm も作る。取りこぼすと意味がない。
        for path in [url, url.appendingPathExtension("wal"), url.appendingPathExtension("shm")] {
            var target = path
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? target.setResourceValues(values)
        }
    }

    func insert(_ location: PendingLocation) {
        context.insert(location)
        save()
    }

    /// 古いものから順に取り出す。送信は先入れ先出し。
    func oldest(limit: Int) -> [PendingLocation] {
        var descriptor = FetchDescriptor<PendingLocation>(
            sortBy: [SortDescriptor(\.recordedAt, order: .forward)]
        )
        descriptor.fetchLimit = limit
        return (try? context.fetch(descriptor)) ?? []
    }

    func count() -> Int {
        (try? context.fetchCount(FetchDescriptor<PendingLocation>())) ?? 0
    }

    func delete(_ location: PendingLocation) {
        context.delete(location)
        save()
    }

    func incrementAttempts(_ location: PendingLocation) {
        location.attempts += 1
        save()
    }

    func clear() {
        try? context.delete(model: PendingLocation.self)
        save()
    }

    /// 溜まりすぎたキューを間引く。長期間圏外だった場合に無限に膨らむのを防ぐ。
    /// 新しいものを `keep` 件だけ残す。
    func trim(to keep: Int) {
        let total = count()
        guard total > keep else { return }
        var descriptor = FetchDescriptor<PendingLocation>(
            sortBy: [SortDescriptor(\.recordedAt, order: .forward)]
        )
        descriptor.fetchLimit = total - keep
        guard let stale = try? context.fetch(descriptor) else { return }
        for row in stale {
            context.delete(row)
        }
        save()
    }

    private func save() {
        guard context.hasChanges else { return }
        do {
            try context.save()
        } catch {
            Log.queue.error("キューを保存できませんでした: \(error.localizedDescription, privacy: .public)")
        }
    }
}
