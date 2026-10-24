package com.yue.music.server;

import com.yue.music.YueMusic;
import com.yue.music.config.MusicConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 房间管理器：每个房间有<b>独立的点歌队列</b>，互不干扰。
 *
 * <p><b>为什么需要房间</b>：一个大服务器上，所有人都听同一首歌会很乱；
 * 分成房间后，几个人可以各听各的（比如「安静听歌房」「电音房」）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>玩家同时只属于一个房间；没主动加房间的人都在 {@link #DEFAULT_ROOM}</li>
 *   <li>每个房间一个 {@link ServerQueue} 实例，队列/进度/播放状态完全独立</li>
 *   <li>点歌、控制、广播都按「玩家所在房间」路由</li>
 * </ul>
 */
public final class RoomManager {

	/** 默认房间（没加房间的人都在这里，保证老行为不变）。 */
	public static final String DEFAULT_ROOM = "default";

	private static final RoomManager INSTANCE = new RoomManager();

	public static RoomManager get() {
		return INSTANCE;
	}

	/** 房间名 → 队列。用 LinkedHashMap 保证 /room list 顺序稳定。 */
	private final Map<String, ServerQueue> rooms = new LinkedHashMap<>();

	/** 玩家 UUID → 房间名。 */
	private final Map<UUID, String> playerRoom = new LinkedHashMap<>();

	/** 房间名 → 创建者名字（显示用）。 */
	private final Map<String, String> roomOwner = new LinkedHashMap<>();

	private RoomManager() {
		rooms.put(DEFAULT_ROOM, new ServerQueue(DEFAULT_ROOM));
		roomOwner.put(DEFAULT_ROOM, "系统");
	}

	// ------------------------------------------------------------ 查询

	/** 玩家所在房间（没记录就是默认房间）。 */
	public synchronized String roomOf(UUID player) {
		String r = playerRoom.get(player);
		if (r == null || !rooms.containsKey(r)) return DEFAULT_ROOM;
		return r;
	}

	/** 玩家所在房间的队列。 */
	public synchronized ServerQueue queueOf(UUID player) {
		return queueFor(roomOf(player));
	}

	/**
	 * 按房间名取队列（不存在则创建，并**自动载入存档**）。
	 *
	 * <p>⚠ 创建时一定要 {@code load()}：以前这里只 {@code new ServerQueue(k)}，
	 * 于是「房间通过这条路径被创建」时存档根本不会被读取 ——
	 * 表现就是「房间回来了但歌全没了」。现在所有创建路径都统一在这里补上。
	 */
	public synchronized ServerQueue queueFor(String room) {
		String key = normalize(room);
		return rooms.computeIfAbsent(key, k -> {
			ServerQueue q = new ServerQueue(k);
			q.load();        // 关键：创建即载入存档
			return q;
		});
	}

	/** 队列 → 房间名（广播时反查用）。 */
	public synchronized String nameOf(ServerQueue queue) {
		for (Map.Entry<String, ServerQueue> e : rooms.entrySet()) {
			if (e.getValue() == queue) return e.getKey();
		}
		return DEFAULT_ROOM;
	}

	/** 房间里的玩家 UUID 列表。 */
	public synchronized List<UUID> playersIn(String room) {
		String key = normalize(room);
		List<UUID> out = new ArrayList<>();
		for (Map.Entry<UUID, String> e : playerRoom.entrySet()) {
			if (key.equals(e.getValue())) out.add(e.getKey());
		}
		return out;
	}

	/** 所有房间名（含默认）。 */
	public synchronized List<String> roomNames() {
		return new ArrayList<>(rooms.keySet());
	}

	public synchronized String ownerOf(String room) {
		return roomOwner.getOrDefault(normalize(room), "?");
	}

	public synchronized int playerCount(String room) {
		return playersIn(room).size();
	}

	public synchronized int roomCount() {
		return rooms.size();
	}

	// ------------------------------------------------------------ 变更

	/**
	 * 加入房间（不存在则创建）。
	 *
	 * @param playerUuid 玩家
	 * @param playerName 玩家名（作为创建者记录）
	 * @param room       房间名
	 * @return 真正加入的房间名
	 */
	public synchronized String join(UUID playerUuid, String playerName, String room) {
		String key = normalize(room);
		rooms.computeIfAbsent(key, k -> {
			YueMusic.LOGGER.info("[{}] 新房间「{}」由 {} 创建", YueMusic.MOD_NAME, k, playerName);
			roomOwner.put(k, playerName);
			ServerQueue q = new ServerQueue(k);
			q.load();      // 关键：房间可能是"卸载过又回来"的，必须读回存档
			return q;
		});
		String old = playerRoom.put(playerUuid, key);
		if (old != null && !old.equals(key)) {
			// 旧房间暂时没人 —— 现在只是记日志，不再卸载（卸载过就丢过歌）
			maybeDropEmpty(old);
		}
		rememberRoom(playerName, key);
		YueMusic.LOGGER.info("[{}] {} 进入房间「{}」（当前 {} 人）",
				YueMusic.MOD_NAME, playerName, key, playersIn(key).size());
		return key;
	}

	/**
	 * 离开当前房间，回到默认房间。
	 *
	 * @return 之前所在的房间名
	 */
	public synchronized String leave(UUID playerUuid, String playerName) {
		String old = playerRoom.remove(playerUuid);
		// 主动离开 = 不想再回那个房间 → 清掉记忆，否则重进又会被送回去
		forgetRoom(playerName);
		if (old == null) return DEFAULT_ROOM;
		maybeDropEmpty(old);
		return old;
	}

	/** 玩家退出服务器：从房间移除（但记住他在哪个房间，下次进服送回去）。 */
	public synchronized void onPlayerQuit(UUID playerUuid) {
		String old = playerRoom.remove(playerUuid);
		// 注意：这里**不**调用 maybeDropEmpty。
		// 因为「退出」只是暂时离开，房间要留着（队列还在放歌），
		// 玩家重进时还要送回同一个房间。
		if (old != null && !DEFAULT_ROOM.equals(old)) {
			YueMusic.LOGGER.info("[{}] 玩家离开房间「{}」（房间保留，等待重进）",
					YueMusic.MOD_NAME, old);
		}
	}

	// ------------------------------------------------------------ 房间记忆（退出重进还原）

	/** 记住某个玩家名所在的房间（写进服务端配置，重启也有效）。 */
	private void rememberRoom(String playerName, String room) {
		if (playerName == null || playerName.isBlank()) return;
		MusicConfig cfg = MusicConfig.get();
		String key = normalize(room);
		String prev = cfg.playerRooms.get(playerName);
		if (key.equals(prev)) return;
		cfg.playerRooms.put(playerName, key);
		cfg.save();
	}

	/** 忘掉某个玩家的房间记忆（他主动离开回默认房间时用）。 */
	private void forgetRoom(String playerName) {
		if (playerName == null || playerName.isBlank()) return;
		MusicConfig cfg = MusicConfig.get();
		if (cfg.playerRooms.remove(playerName) != null) cfg.save();
	}

	/**
	 * 玩家进服时把他送回上次所在的房间。
	 *
	 * <p>这就是「在房间2退出，重进还应该在房间2」的实现。
	 *
	 * @return 还原到的房间名（没有记忆或记忆的房间已不存在则返回默认房间）
	 */
	public synchronized String restoreRoom(UUID playerUuid, String playerName) {
		if (playerName == null || playerName.isBlank()) return DEFAULT_ROOM;
		MusicConfig cfg = MusicConfig.get();
		String remembered = cfg.playerRooms.get(playerName);
		if (remembered == null || remembered.isBlank()) return DEFAULT_ROOM;
		String key = normalize(remembered);
		if (DEFAULT_ROOM.equals(key)) return DEFAULT_ROOM;

		// 房间对象可能因为「服务器重启」或「房间被卸载」而消失
		// → 重新建一个，并且**一定从存档载入**（这就是 20 首导入的歌能回来的关键）
		rooms.computeIfAbsent(key, k -> {
			YueMusic.LOGGER.info("[{}] 房间「{}」重建（玩家 {} 回来）", YueMusic.MOD_NAME, k, playerName);
			roomOwner.putIfAbsent(k, playerName);
			ServerQueue q = new ServerQueue(k);
			q.load();
			return q;
		});
		playerRoom.put(playerUuid, key);
		YueMusic.LOGGER.info("[{}] {} 回到房间「{}」（{} 首）",
				YueMusic.MOD_NAME, playerName, key, queueFor(key).entries().size());
		return key;
	}

	/** 清掉所有房间记忆（管理用）。 */
	public synchronized void clearRoomMemory() {
		MusicConfig cfg = MusicConfig.get();
		cfg.playerRooms.clear();
		cfg.save();
	}

	/**
	 * 把所有房间的队列都写盘（服务器关闭时调用）。
	 *
	 * <p>队列本身已经会在每次变动时 {@code autosave()}，这里是**兜底**：
	 * 保证关闭瞬间内存里的状态和磁盘完全一致。
	 */
	public synchronized void saveAll() {
		for (Map.Entry<String, ServerQueue> e : rooms.entrySet()) {
			e.getValue().save();
		}
	}

	/**
	 * 房间空了时的处理。
	 *
	 * <p><b>为什么现在什么都不做（不卸载）</b>：
	 * 以前房间没人就 {@code rooms.remove(room)}，把队列从内存丢掉，
	 * 只留一份磁盘存档，指望「回来时 load 回来」。
	 * 实际用起来这条路会丢歌 —— 日志里就出现过
	 * 「导入 45 首 → 切房间 → 回来 0 首」。
	 *
	 * <p>现在的原则：<b>空房间也把队列留在内存里</b>（同时磁盘也有一份）。
	 * 内存里那份永远不会因为「谁走了」而丢，这是最可靠的。
	 * 房间只在<b>服务器关闭</b>时才整体释放。
	 *
	 * <p>代价是空房间会一直占着内存（每首只有歌名/ID/时长，很轻），
	 * 对单人/小服完全无所谓；真有需要可以用 {@code /SyMus room cleanup} 清。
	 */
	private void maybeDropEmpty(String room) {
		if (DEFAULT_ROOM.equals(room)) return;
		int n = playersIn(room).size();
		if (n == 0) {
			// 只是日志，不卸载 —— 卸载过一次就丢过一次歌
			ServerQueue q = rooms.get(room);
			int songs = q == null ? 0 : q.entries().size();
			YueMusic.LOGGER.info("[{}] 房间「{}」暂时没人（保留 {} 首在内存，随时可回来）",
					YueMusic.MOD_NAME, room, songs);
		}
	}

	/**
	 * 清理长时间没人的空房间（管理用，可选）。
	 *
	 * @return 被清理的房间数
	 */
	public synchronized int cleanupEmptyRooms() {
		int removed = 0;
		java.util.List<String> names = new java.util.ArrayList<>(rooms.keySet());
		for (String r : names) {
			if (DEFAULT_ROOM.equals(r)) continue;
			if (playersIn(r).isEmpty()) {
				ServerQueue q = rooms.remove(r);
				if (q != null) q.save();      // 先落盘再释放
				roomOwner.remove(r);
				removed++;
				YueMusic.LOGGER.info("[{}] 已清理空房间「{}」（存档保留）", YueMusic.MOD_NAME, r);
			}
		}
		return removed;
	}

	/** 房间名规范化：小写、去空白、限长；空名归到默认房间。 */
	public static String normalize(String room) {
		if (room == null) return DEFAULT_ROOM;
		String r = room.trim().toLowerCase();
		if (r.isEmpty()) return DEFAULT_ROOM;
		// 只留字母数字下划线中文，避免奇怪的名字破坏指令/显示
		r = r.replaceAll("[^a-z0-9_\\u4e00-\\u9fa5]", "");
		if (r.isEmpty()) return DEFAULT_ROOM;
		return r.length() > 16 ? r.substring(0, 16) : r;
	}
}
