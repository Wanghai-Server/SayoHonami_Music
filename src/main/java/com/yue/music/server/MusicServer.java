package com.yue.music.server;

import com.yue.music.YueMusic;
import com.yue.music.config.MusicConfig;
import com.yue.music.net.MusicNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端逻辑：注册网络包、处理点歌与控制、广播队列。
 *
 * <p>「装了模组的玩家」= 能用 {@link ServerPlayNetworking#canSend} 收到我们的包的玩家。
 * 没装模组的原版玩家不会收到任何自定义包，也不会掉线；他们只会看到聊天栏里的点歌提示。
 */
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import com.yue.music.model.MusicTrack;

import com.yue.music.model.PlayUrl;

public final class MusicServer {

	private MusicServer() {
	}

	/** 每个玩家的点歌冷却。 */
	private static final Map<UUID, Long> COOLDOWN = new HashMap<>(16);

	private static MinecraftServer server;

	public static void init() {
		// 1) 注册包类型（必须双端都注册，客户端在客户端初始化里注册 S2C）
		PayloadTypeRegistry.playC2S().register(MusicNetworking.AddSongC2S.ID, MusicNetworking.AddSongC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(MusicNetworking.ControlC2S.ID, MusicNetworking.ControlC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(MusicNetworking.RequestSyncC2S.ID, MusicNetworking.RequestSyncC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(MusicNetworking.RoomC2S.ID, MusicNetworking.RoomC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(MusicNetworking.ImportPlaylistC2S.ID,
				MusicNetworking.ImportPlaylistC2S.CODEC);
		PayloadTypeRegistry.playC2S().register(MusicNetworking.SetLimitC2S.ID,
				MusicNetworking.SetLimitC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(MusicNetworking.QueueS2C.ID, MusicNetworking.QueueS2C.CODEC);

		// 2) 注册处理器
		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.AddSongC2S.ID,
				(payload, context) -> onAddSong(payload, context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.ControlC2S.ID,
				(payload, context) -> onControl(payload, context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.ImportPlaylistC2S.ID,
				(payload, context) -> onImportPlaylist(payload, context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.SetLimitC2S.ID,
				(payload, context) -> onSetLimit(payload, context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.RequestSyncC2S.ID,
				(payload, context) -> sendTo(null, context.player()));
		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.RoomC2S.ID,
				(payload, context) -> onRoom(payload, context.player()));

		// 3) 生命周期：载入/保存队列 + 载入服务端登录态
		ServerLifecycleEvents.SERVER_STARTED.register(s -> {
			server = s;
			ServerQueue.get().load();
			ServerLogin.get().load();
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(s -> RoomManager.get().saveAll());

		// 3.1) 注册「队列里缺直链时去解析」的回调（歌单导入的歌靠它）
		ServerQueue.setUrlResolver(MusicServer::resolveForQueue);

		// 3.2) 玩家退出：从房间移除，空房间自动回收
		ServerPlayConnectionEvents.DISCONNECT.register((handler, s) ->
				RoomManager.get().onPlayerQuit(handler.getPlayer().getUuid()));

		// 3.5) /SyMus 指令（status / login / logout）
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> MusicCommand.init(dispatcher));

		// 4) 玩家进服：送回上次所在的房间 + 补发一次队列状态
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			server = s;
			ServerPlayerEntity player = handler.getPlayer();
			// 立刻还原房间（「在房间2退出，重进还应该在房间2」）
			String restored = RoomManager.get().restoreRoom(
					player.getUuid(), player.getGameProfile().name());
			// 等 1 秒再发，避免客户端还没准备好接收
			new Thread(() -> {
				try {
					Thread.sleep(1200);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				s.execute(() -> {
					ServerQueue q = RoomManager.get().queueOf(player.getUuid());
					logState(q, "玩家进服 " + player.getGameProfile().name());
					// 只把「他自己房间」的状态发给他
					sendTo(RoomManager.DEFAULT_ROOM.equals(restored)
							? "欢迎回来，当前房间「" + q.room() + "」"
							: "欢迎回来，已回到房间「" + restored + "」", player);
				});
			}, "YueMusic-JoinSync").start();
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(s -> server = null);

		YueMusic.LOGGER.info("[YueMusic] 服务端点歌系统就绪");
	}

	// ---------------------------------------------------------------- 处理

	private static void onAddSong(MusicNetworking.AddSongC2S payload, ServerPlayerEntity player) {
		MusicConfig cfg = MusicConfig.get();
		if (!cfg.allowPlayerRequest && !isOp(player)) {
			notify(player, "本服已关闭玩家点歌");
			return;
		}
		// 冷却
		long now = System.currentTimeMillis();
		long last = COOLDOWN.getOrDefault(player.getUuid(), 0L);
		int cd = Math.max(0, cfg.requestCooldownSeconds) * 1000;
		if (cd > 0 && now - last < cd) {
			long wait = (cd - (now - last)) / 1000 + 1;
			notify(player, "点歌太快啦，还要等 " + wait + " 秒");
			return;
		}

		MusicTrack track;
		try {
			track = MusicNetworking.decodeTrack(payload.trackJson());
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 点歌数据解析失败: {}", e.toString());
			notify(player, "点歌失败：" + e.getMessage());
			return;
		}

		String name = player.getGameProfile().name();

		// 服务端已登录 → 用服务端的账号解析直链（所有玩家免登录的关键）。
		// 解析要联网，必须离开服务端线程；解析完再回到主线程入队。
		if (ServerLogin.get().loggedIn()) {
			COOLDOWN.put(player.getUuid(), now);
			notify(player, "正在用服务端账号解析《" + track.title() + "》…");
			new Thread(() -> {
				PlayUrl url;
				try {
					url = ServerLogin.get().resolve(track);
				} catch (Exception e) {
					url = PlayUrl.fail(e.getMessage() == null ? "解析异常" : e.getMessage());
				}
				final PlayUrl resolved = url;
				MinecraftServer s = server;
				if (s == null) return;
				s.execute(() -> enqueue(player, track, name, resolved));
			}, "YueMusic-ServerResolve").start();
			return;
		}

		// 服务端没登录 → 沿用客户端解析好的直链（旧行为）
		if (!ServerQueue.isTrustedAudioUrl(payload.url())) {
			notify(player, "音频地址不在受信任范围，已拒绝");
			return;
		}
		try {
			ServerQueue q = RoomManager.get().queueOf(player.getUuid());
			String msg = q.add(new QueueEntry(track, payload.url(), payload.duration(), name));
			COOLDOWN.put(player.getUuid(), now);
			YueMusic.LOGGER.info("[YueMusic] {} 在房间「{}」点歌: {}", name, q.room(), track.display());
			broadcast(q, msg, null);
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 点歌处理失败: {}", e.toString());
			notify(player, "点歌失败：" + e.getMessage());
		}
	}

	/**
	 * 客户端设置页那个「队列上限」按钮真正生效的路径。
	 *
	 * <p>队列上限由服务端判定，而模组配置两端各读各的文件 ——
	 * 所以客户端不能只改自己的配置，必须把新上限发过来由服务端写进<b>服务端配置</b>。
	 *
	 * <p>权限：需要 OP 2 级（和 {@code /SyMus limit} 一致）。
	 */
	private static void onSetLimit(MusicNetworking.SetLimitC2S payload, ServerPlayerEntity player) {
		String name = player.getGameProfile().name();
		ServerQueue q = RoomManager.get().queueOf(player.getUuid());

		boolean admin;
		try {
			admin = player.isCreativeLevelTwoOp();
		} catch (Throwable t) {
			admin = false;
		}
		if (!admin) {
			YueMusic.LOGGER.info("[YueMusic] {} 想把队列上限改成 {}，但权限不足（< 2 级）",
					name, payload.limit());
			notify(player, "改队列上限需要 OP 权限（让服主用 /SyMus limit " + payload.limit() + "）");
			return;
		}

		MusicConfig cfg = MusicConfig.get();
		int old = cfg.maxQueueSize;
		int applied = Math.max(1, Math.min(MusicConfig.MAX_QUEUE_LIMIT, payload.limit()));
		cfg.maxQueueSize = applied;
		cfg.save();
		YueMusic.LOGGER.info("[YueMusic] {} 把服务端队列上限从 {} 改为 {}", name, old, applied);
		broadcast(q, "队列上限已设为 " + applied + " 首（原 " + old + "）", null);
	}

	/**
	 * 批量导入歌单：把歌单里的歌一次性加进点歌人所在房间的队列。
	 *
	 * <p><b>关键设计</b>：导入时**不解析播放地址**（url 留空），
	 * 由服务端在真正播到那一首时再解析（见 {@link #resolveForQueue}）。
	 * 否则导入 1000 首要现场解析 1000 次 —— 既慢又会触发音乐平台风控。
	 *
	 * <p>队列上限用**本房间**的 {@code limitOf(room)}（已支持到 10000），
	 * 但导入时**自动去重**（同一首歌不重复入队）。
	 */
	private static void onImportPlaylist(MusicNetworking.ImportPlaylistC2S payload,
										 ServerPlayerEntity player) {
		String name = player.getGameProfile().name();
		java.util.List<MusicTrack> tracks;
		try {
			tracks = MusicNetworking.decodeTracksJson(payload.tracksJson());
		} catch (Exception e) {
			notify(player, "歌单数据解析失败：" + e.getMessage());
			return;
		}
		if (tracks.isEmpty()) {
			notify(player, "歌单里没有可导入的歌曲");
			return;
		}

		ServerQueue q = RoomManager.get().queueOf(player.getUuid());
		// 用「本房间」自己的上限（/SyMus limit <房间> <数字> 可单独设置）
		int limit = MusicConfig.get().limitOf(q.room());

		// 已在队列里的歌（按 source:id 去重，避免重复导入同一个歌单）
		java.util.Set<String> existing = new java.util.HashSet<>();
		for (QueueEntry e : q.entries()) {
			existing.add(e.track().source() + ":" + e.track().id());
		}

		int added = 0;
		int skippedDup = 0;
		int skippedFull = 0;
		for (MusicTrack t : tracks) {
			String key = t.source() + ":" + t.id();
			if (existing.contains(key)) {
				skippedDup++;
				continue;
			}
			if (q.entries().size() + added >= limit) {
				skippedFull++;
				continue;
			}
			// url 留空：播到这首时由服务端解析
			q.add(new QueueEntry(t, "", t.duration(), name));
			existing.add(key);
			added++;
		}

		boolean first = q.currentIndex() < 0;
		if (first && added > 0) {
			// 队列原本是空的 → 立刻开始播第一首
			q.nextFromEmpty();
		}

		String pname = payload.playlistName() == null || payload.playlistName().isBlank()
				? "歌单" : payload.playlistName();
		StringBuilder msg = new StringBuilder("导入《" + pname + "》：成功 " + added + " 首");
		if (skippedDup > 0) msg.append("，跳过重复 ").append(skippedDup).append(" 首");
		if (skippedFull > 0) msg.append("，超上限跳过 ").append(skippedFull).append(" 首");
		YueMusic.LOGGER.info("[YueMusic] {} 在房间「{}」导入歌单「{}」：+{}（重复{}，超限{}）",
				name, q.room(), pname, added, skippedDup, skippedFull);
		broadcast(q, msg.toString(), null);

		// 立刻在后台**批量预解析**这些歌的直链。
		//
		// 为什么不能等「播到那首再解析」：解析要 1~3 秒，而歌是一首接一首放的 ——
		// 上一首放完时下一首还没解析好，就会卡住/被跳过。
		// 这也是「歌单导入的（VIP）歌播不了」的一个直接原因。
		// 这里只提前解析前面一段（20 首足够覆盖播放提前量），
		// 剩下的仍然按需解析，避免一次打太多请求被风控。
		if (ServerLogin.get().loggedIn() && added > 0) {
			preResolveAhead(q, 20);
		}
	}

	/**
	 * 后台预解析队列里「还没有直链」的前 N 首。
	 *
	 * <p>串行 + 间隔，避免一瞬间发几十个请求触发音乐平台风控。
	 */
	private static void preResolveAhead(ServerQueue q, int max) {
		java.util.List<Integer> need = new java.util.ArrayList<>();
		java.util.List<QueueEntry> snap = q.entries();
		for (int i = 0; i < snap.size() && need.size() < max; i++) {
			QueueEntry e = snap.get(i);
			if (e.url() == null || e.url().isBlank()) need.add(i);
		}
		if (need.isEmpty()) return;
		YueMusic.LOGGER.info("[YueMusic] 房间「{}」预解析 {} 首的直链（后台进行）",
				q.room(), need.size());
		new Thread(() -> {
			for (int idx : need) {
				MinecraftServer s = server;
				if (s == null) return;
				// 已经补上了就跳过（可能已经被播放流程解析过）
				java.util.List<QueueEntry> now = q.entries();
				if (idx >= now.size()) continue;
				QueueEntry cur = now.get(idx);
				if (cur.url() != null && !cur.url().isBlank()) continue;
				resolveForQueue(q, idx, cur.track());
				try {
					Thread.sleep(250);   // 温和一点，别触发风控
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "YueMusic-PreResolve").start();
	}

	/**
	 * 给「队列里某首还没解析过地址的歌」解析播放地址。
	 *
	 * <p><b>会联网</b>，必须在后台线程调用；解析完自己回到主线程写回队列并广播。
	 *
	 * <p><b>为什么要有重试</b>：以前只试一次，失败就永远空着 ——
	 * 表现就是「歌单导入的歌（尤其 VIP 那几首）播不了」，而且再也救不回来。
	 * 现在失败会**退避重试最多 3 次**（2s / 5s / 12s），
	 * 网络抖动、vkey 临时抽风都能自己恢复。
	 */
	public static void resolveForQueue(ServerQueue q, int index, MusicTrack track) {
		resolveForQueue(q, index, track, 0);
	}

	/** 最多重试次数。 */
	private static final int RESOLVE_MAX_RETRY = 3;

	private static void resolveForQueue(ServerQueue q, int index, MusicTrack track, int attempt) {
		if (!ServerLogin.get().loggedIn()) {
			YueMusic.LOGGER.warn("[YueMusic] 队列里《{}》没有直链，且服务端未登录 → 改由点歌客户端解析",
					track.title());
			return;
		}
		new Thread(() -> {
			PlayUrl url;
			try {
				url = ServerLogin.get().resolve(track);
			} catch (Exception e) {
				url = PlayUrl.fail(e.getMessage() == null ? "解析异常" : e.getMessage());
			}
			final PlayUrl resolved = url;
			MinecraftServer s = server;
			if (s == null) return;
			s.execute(() -> {
				if (!resolved.ok()) {
					String err = resolved.error() == null ? "未知原因" : resolved.error();
					// 会员限制重试也没用，直接告诉玩家；其它错误退避重试
					boolean vipBlocked = err.contains("会员");
					if (!vipBlocked && attempt < RESOLVE_MAX_RETRY) {
						YueMusic.LOGGER.info("[YueMusic] 《{}》第 {} 次解析失败（{}），稍后重试",
								track.title(), attempt + 1, err);
						retryLater(q, index, track, attempt + 1);
						return;
					}
					YueMusic.LOGGER.warn("[YueMusic] 队列解析失败《{}》（试了 {} 次）：{}",
							track.title(), attempt + 1, err);
					broadcast(q, "《" + track.title() + "》放不了：" + err
							+ (vipBlocked ? "（这是账号权限问题，换个有会员的 QQ 登服务端即可）" : ""), null);
					return;
				}
				boolean ok = q.fillUrl(index, resolved.url());
				if (ok) {
					YueMusic.LOGGER.info("[YueMusic] 队列第 {} 首《{}》地址已补齐（{}{}）",
							index + 1, track.title(), resolved.quality(),
							attempt > 0 ? "，第 " + (attempt + 1) + " 次成功" : "");
					broadcast(q, null, null);
				}
			});
		}, "YueMusic-QueueResolve").start();
	}

	/** 退避重试：2s / 5s / 12s。 */
	private static void retryLater(ServerQueue q, int index, MusicTrack track, int attempt) {
		long[] delays = {2000, 5000, 12000};
		long d = delays[Math.min(attempt - 1, delays.length - 1)];
		new Thread(() -> {
			try {
				Thread.sleep(d);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			resolveForQueue(q, index, track, attempt);
		}, "YueMusic-ResolveRetry").start();
	}

	/** 服务端解析完成后入队（在主线程执行）。 */	private static void enqueue(ServerPlayerEntity player, MusicTrack track, String name, PlayUrl resolved) {
		if (!resolved.ok()) {
			// 服务端解析失败（Cookie 过期 / 风控）→ 退回让点歌的客户端自己解析，
			// 不然全服就一起放不了歌了。
			notify(player, "服务端账号放不了《" + track.title() + "》（"
					+ resolved.error() + "），已改由本机解析");
			YueMusic.LOGGER.warn("[YueMusic] 服务端解析失败，回落客户端解析: {} / {}",
					track.display(), resolved.error());
			fallbackClientResolve(player, track, name);
			return;
		}
		if (!ServerQueue.isTrustedAudioUrl(resolved.url())) {
			notify(player, "解析出的地址不在受信任范围，已拒绝");
			return;
		}
		try {
			ServerQueue q = RoomManager.get().queueOf(player.getUuid());
			String msg = q.add(new QueueEntry(track, resolved.url(), track.duration(), name));
			YueMusic.LOGGER.info("[YueMusic] {} 在房间「{}」点歌（服务端解析）: {} [{}]",
					name, q.room(), track.display(), resolved.quality());
			logState(q, "点歌入队后");
			broadcast(q, msg, null);
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 服务端解析入队失败: {}", e.toString());
			notify(player, "点歌失败：" + e.getMessage());
		}
	}

	/**
	 * 服务端解析不了时，告诉点歌的客户端「你自己解析一下再发我」。
	 *
	 * <p>客户端收到 {@code NEED_CLIENT_RESOLVE} 后会走它自己的解析逻辑（可能它自己登录过）。
	 * 这样即使服主的 Cookie 过期，也不会全服一起放不了歌。
	 */
	private static void fallbackClientResolve(ServerPlayerEntity player, MusicTrack track, String name) {
		if (!ServerPlayNetworking.canSend(player, MusicNetworking.QueueS2C.ID)) {
			return; // 原版客户端没法处理，只能算了
		}
		// 直接发一个带 notice 的状态，客户端看到这个 notice 会自己重试
		ServerQueue q = RoomManager.get().queueOf(player.getUuid());
		ServerPlayNetworking.send(player, stateOf(q, MusicNetworking.NEED_CLIENT_RESOLVE + track.id()));
	}

	private static void onControl(MusicNetworking.ControlC2S payload, ServerPlayerEntity player) {
		// 控制只作用于**玩家自己所在的房间**，不会影响别的房间
		ServerQueue q = RoomManager.get().queueOf(player.getUuid());
		String who = player.getGameProfile().name();
		// PLAY 带下标时表示「插播第 N 首」；不带（0）时表示「继续播放」
		if (payload.action() == MusicNetworking.Action.PLAY && payload.value() > 0) {
			q.playNow(payload.value());
			broadcast(q, who + " 插播了《" + (q.current() == null ? "?" : q.current().track().title())
					+ "》", null);
			return;
		}
		switch (payload.action()) {
			case PLAY -> q.setPlaying(true);
			case PAUSE -> q.setPlaying(false);
			case RESUME -> q.setPlaying(true);
			case NEXT -> q.next();
			case PREV -> q.prev();
			case STOP -> {
				q.clear();
				broadcast(q, who + " 停止了播放并清空队列", null);
				return;
			}
			case CLEAR -> {
				q.clear();
				broadcast(q, who + " 清空了点歌队列", null);
				return;
			}
			case SEEK -> q.seek(payload.value());
			case MODE -> {
				ServerQueue.PlayMode m = q.cyclePlayMode();
				broadcast(q, who + " 把播放模式切成了「" + m.cn() + "」", null);
			}
			// 音量：**只记住，不广播**。
			// 以前这里 broadcast，导致「一个人调音量所有人跟着变」。
			// 现在每个人管自己的音量，服务端只作为「新玩家进服时的默认值」。
			case SET_VOLUME -> {
				q.setVolume(payload.value());
				return;
			}
			case REMOVE -> {
				String removed = q.remove(payload.value());
				if (removed == null) return;
				broadcast(q, who + " 从队列删除了《" + removed + "》", null);
				return;
			}
		}
		YueMusic.LOGGER.debug("[{}] {} 在房间「{}」执行控制 {}",
				YueMusic.MOD_NAME, who, q.room(), payload.action());
		broadcast(q, null, null);
	}

	/** 是否管理员（权限等级 ≥ 2，即 OP / 管理员）。 */
	private static boolean isOp(ServerPlayerEntity player) {
		return player.isCreativeLevelTwoOp();
	}

	private static void notify(ServerPlayerEntity player, String text) {
		if (ServerPlayNetworking.canSend(player, MusicNetworking.QueueS2C.ID)) {
			ServerPlayNetworking.send(player, stateOf(RoomManager.get().queueOf(player.getUuid()), text));
		} else {
			player.sendMessage(net.minecraft.text.Text.literal("[点歌台] " + text));
		}
	}

	/** 组装某个房间的状态包（连「服务端解析好的直链」一起发，客户端不需要登录）。 */
	private static MusicNetworking.QueueS2C stateOf(ServerQueue q, String notice) {
		String json = MusicNetworking.encodeQueue(q.entries());
		// 队列太长（上百首）就先压缩：GZIP+Base64 通常只剩 1/10，
		// 避免再出现 "String too big (was 85423, max 32767)" 把玩家踢下线
		Object[] packed = MusicNetworking.encodeQueueField(json);
		return new MusicNetworking.QueueS2C((String) packed[0], q.currentIndex(), q.playing(),
				q.startEpochMs(), q.playMode().name(), q.volume(), notice == null ? "" : notice,
				ServerLogin.get().loggedIn(), q.room(), q.safePositionMs(),
				MusicConfig.get().limitOf(q.room()), (Boolean) packed[1]);
	}

	/** 诊断：把要广播的关键信息打出来（排查「听不到 / 从头开始」）。 */
	private static void logState(ServerQueue q, String why) {
		QueueEntry cur = q.current();
		boolean hasUrl = cur != null && cur.url() != null && !cur.url().isBlank();
		YueMusic.LOGGER.info("[YueMusic] 广播({}) 房间={} index={} playing={} 已播={}s 直链={} 曲目={}",
				why, q.room(), q.currentIndex(), q.playing(), q.positionSeconds(),
				hasUrl ? "有" : "无",
				cur == null ? "(空)" : cur.track().display());
	}

	/**
	 * 把某个房间的状态广播给<b>该房间内</b>的所有玩家。
	 *
	 * <p>房间之间互不干扰：A 房间切歌不会影响 B 房间正在听的歌。
	 *
	 * @param q      房间队列
	 * @param notice 聊天提示（发到全服聊天栏，含原版玩家）
	 * @param only   只发给这一个玩家（仍需在同一房间内）；null 表示发全房间
	 */
	public static void broadcast(ServerQueue q, String notice, ServerPlayerEntity only) {
		if (server == null) return;
		if (only != null) {
			if (ServerPlayNetworking.canSend(only, MusicNetworking.QueueS2C.ID)) {
				ServerPlayNetworking.send(only, stateOf(q, notice));
			}
		} else {
			String room = RoomManager.get().nameOf(q);
			for (UUID id : RoomManager.get().playersIn(room)) {
				ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
				if (p != null && ServerPlayNetworking.canSend(p, MusicNetworking.QueueS2C.ID)) {
					// 每个玩家单独组包：OP 等级是「因人而异」的
					ServerPlayNetworking.send(p, stateOf(q, notice));
				}
			}
		}
		if (notice != null) {
			// 提示**只发给本房间的人**。
			// 以前用 playerManager.broadcast() 是全服可见的 —— 于是
			// 「A 在 room1 点歌，B 在 room3 也能看到提示」，等于房间没隔离。
			// 现在按房间逐个 sendMessage：这是原版机制，没装 mod 的玩家也能看到。
			sendRoomNotice(q, notice, only);
		}
	}

	/**
	 * 把聊天提示发给「指定房间」里的玩家。
	 *
	 * @param q      房间队列
	 * @param notice 提示文本
	 * @param only   只发给这一个玩家；null 表示发全房间
	 */
	private static void sendRoomNotice(ServerQueue q, String notice, ServerPlayerEntity only) {
		if (server == null) return;
		net.minecraft.text.Text msg = net.minecraft.text.Text.literal("[点歌台] " + notice);
		if (only != null) {
			only.sendMessage(msg, false);
			return;
		}
		String room = RoomManager.get().nameOf(q);
		for (UUID id : RoomManager.get().playersIn(room)) {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
			if (p != null) p.sendMessage(msg, false);
		}
	}

	/** 只把状态发给一个玩家（他所在房间的状态）。 */
	private static void sendTo(String notice, ServerPlayerEntity player) {
		ServerQueue q = RoomManager.get().queueOf(player.getUuid());
		if (ServerPlayNetworking.canSend(player, MusicNetworking.QueueS2C.ID)) {
			ServerPlayNetworking.send(player, stateOf(q, notice));
		}
	}

	/** 玩家换了房间后立刻同步一次新房间的状态（指令切房间时用）。 */
	public static void syncRoom(ServerPlayerEntity player) {
		sendTo("当前房间：" + RoomManager.get().roomOf(player.getUuid()), player);
	}

	/** 处理房间操作（加入 / 离开 / 查看）。 */
	private static void onRoom(MusicNetworking.RoomC2S payload, ServerPlayerEntity player) {
		RoomManager rm = RoomManager.get();
		String name = player.getGameProfile().name();
		UUID id = player.getUuid();

		switch (payload.action()) {
			case JOIN -> {
				String want = RoomManager.normalize(payload.room());
				String joined = rm.join(id, name, want);
				sendTo("已进入房间「" + joined + "」"
						+ (joined.equals(RoomManager.DEFAULT_ROOM) ? "" : "（" + rm.playerCount(joined) + " 人）"),
						player);
				// 进新房间立刻同步一次该房间的队列（含服务端直链）
				broadcast(rm.queueFor(joined), null, player);
			}
			case LEAVE -> {
				String old = rm.leave(id, name);
				sendTo("已离开房间「" + old + "」，回到默认房间", player);
				broadcast(rm.queueFor(RoomManager.DEFAULT_ROOM), null, player);
			}
			case LIST -> {
				StringBuilder sb = new StringBuilder("房间列表：");
				for (String r : rm.roomNames()) {
					sb.append("\n  · ").append(r)
							.append("（").append(rm.playerCount(r)).append(" 人")
							.append("，队列 ").append(rm.queueFor(r).entries().size()).append(" 首）");
					if (r.equals(rm.roomOf(id))) sb.append(" ← 你在这里");
				}
				sendTo(sb.toString(), player);
			}
		}
	}

	/**
	 * 每 5 秒校正一次进度 + **由服务端负责推进到下一首**（所有房间）。
	 *
	 * <p><b>为什么必须服务端推进</b>：以前只靠客户端「本地播完」再上报 NEXT。
	 * 如果某个客户端一直放不出声（网络慢/下载失败），它永远等不到「播完」，
	 * 于是整首歌卡死 —— 这就是「不会自动切换下一首」的根因。
	 * 现在服务端按自己记的开播时间 + 歌曲时长来判断，到点就切，与客户端无关。
	 */
	public static void tick() {
		if (server == null) return;
		if (server.getTicks() % 100 != 0) return;   // 每 5 秒
		for (String room : RoomManager.get().roomNames()) {
			tickRoom(RoomManager.get().queueFor(room));
		}
	}

	/**
	 * 自动切歌的容差（秒）。
	 *
	 * <p><b>为什么需要这么大的容差</b>：这里的「时长」来自**搜索接口的元数据**
	 * （QQ音乐的 {@code interval} 字段，整数秒），它<span>不一定等于真实音频长度</span> ——
	 * 常见情况是元数据比真实音频**短**（少几秒到十几秒）。
	 * 容差太小就会在歌还没放完时切掉，玩家听到的就是「<b>提前切歌</b>」。
	 *
	 * <p>宁可多放几秒（客户端自己放完会静音），也不要早切 ——
	 * 早切是明显的 bug，多等几秒只是短暂安静。
	 */
	private static final int AUTO_NEXT_TOLERANCE_SECONDS = 12;

	private static void tickRoom(ServerQueue q) {
		if (!q.playing() || q.current() == null) return;

		QueueEntry cur = q.current();
		int dur = cur.duration() > 0 ? cur.duration() : cur.track().duration();
		if (dur > 0) {
			int pos = q.positionSeconds();
			if (pos >= dur + AUTO_NEXT_TOLERANCE_SECONDS) {
				YueMusic.LOGGER.info("[YueMusic] 房间「{}」《{}》播放结束（进度 {}s / 元数据时长 {}s + 容差 {}s），自动切下一首",
						q.room(), cur.track().title(), pos, dur, AUTO_NEXT_TOLERANCE_SECONDS);
				q.onTrackFinished();
				broadcast(q, null, null);
				return;
			}
		} else if (q.positionSeconds() > 15 * 60) {
			// 时长未知的兜底：超过 15 分钟当作放完，避免永久卡住
			YueMusic.LOGGER.warn("[YueMusic] 房间「{}」《{}》时长未知且已播放 15 分钟，强制切歌",
					q.room(), cur.track().title());
			q.onTrackFinished();
			broadcast(q, null, null);
			return;
		}
		broadcast(q, null, null);
	}
}
