package com.yue.music.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.yue.music.YueMusic;
import com.yue.music.config.MusicConfig;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * {@code /SyMus} 指令（Brigadier 只允许小写，所以实际输入是 {@code /symus}）。
 *
 * <ul>
 *   <li>{@code /symus status} — 查看服务端登录状态、队列、当前播放</li>
 *   <li>{@code /symus login <cookie>} — 用浏览器 Cookie 登录（登录后所有玩家免登录）</li>
 *   <li>{@code /symus logout} — 退出登录</li>
 * </ul>
 *
 * <p>需要 OP 权限等级 2（和 {@code /gamemode} 同级）。
 */
public final class MusicCommand {

	/** 显示名（用户看到的样子）。Brigadier 内部用小写。 */
	public static final String DISPLAY = "SyMus";
	public static final String LITERAL = "symus";

	private MusicCommand() {
	}

	/**
	 * 注册指令。
	 *
	 * <p><b>权限设计</b>：
	 * <ul>
	 *   <li>{@code /symus status} —— <b>所有人可用</b>，方便玩家自己确认当歌台状态</li>
	 *   <li>{@code /symus room ...} —— <b>所有人可用</b>，玩家自己分房间</li>
	 *   <li>{@code /symus login} / {@code /symus logout} —— 需要 <b>4 级 OP</b>
	 *       （涉及服主账号凭证，只给最高权限；服务器控制台同样是 4 级，
	 *        所以也可以在控制台直接敲，Cookie 太长时更方便）</li>
	 * </ul>
	 */
	public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
		// 1) 人人可用：只能看队列状态，看不到账号名
		dispatcher.register(CommandManager.literal(LITERAL)
				.then(CommandManager.literal("status")
						.executes(MusicCommand::status))
				.executes(MusicCommand::status));

		// 2) 需要 4 级 OP（OWNERS）：管理服务端登录态。
		//    servers：控制台/RCON 天然是 4 级，所以服主仍可用控制台操作。
		//    注意这里**不能**再挂 status 节点——Brigadier 会把同名子节点合并，
		//    那样普通玩家就又能从这条命令走到 status 了。
		dispatcher.register(CommandManager.literal(LITERAL)
				.requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))
				.then(CommandManager.literal("login")
						.then(CommandManager.argument("cookie", StringArgumentType.greedyString())
								.executes(MusicCommand::login)))
				.then(CommandManager.literal("logout")
						.executes(MusicCommand::logout)));

		// 3) 房间：所有人可用（把自己切到某个房间，各自听各自的歌）
		dispatcher.register(CommandManager.literal(LITERAL)
				.then(CommandManager.literal("room")
						.then(CommandManager.literal("join")
								.then(CommandManager.argument("name", StringArgumentType.word())
										.executes(MusicCommand::roomJoin)))
						.then(CommandManager.literal("leave")
								.executes(MusicCommand::roomLeave))
						.then(CommandManager.literal("list")
								.executes(MusicCommand::rooms))));

		// 4) 房间清理：需要 OP（会释放别人房间的内存，不能让普通玩家碰）
		dispatcher.register(CommandManager.literal(LITERAL)
				.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
				.then(CommandManager.literal("room")
						.then(CommandManager.literal("cleanup")
								.executes(MusicCommand::roomCleanup))));

		// 5) 队列上限：需要 OP（改的是服务端配置里的全局上限）
		//    用法：/SyMus limit <上限数字>
		dispatcher.register(CommandManager.literal(LITERAL)
				.requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
				.then(CommandManager.literal("limit")
						.then(CommandManager.argument("limit", IntegerArgumentType.integer(1, MusicConfig.MAX_QUEUE_LIMIT))
								.executes(MusicCommand::setGlobalLimit))));
	}

	/**
	 * 改「服务端配置里的队列上限」。
	 *
	 * <p>用法：{@code /SyMus limit <上限数字>}（例如 {@code /SyMus limit 1000}）
	 *
	 * <p><b>为什么需要它</b>：队列上限由<b>服务端</b>判定（导入歌单、点歌都走服务端），
	 * 而模组配置是两端各读各的文件 —— 在客户端设置页改的那个值，
	 * 服务端根本不知道，于是会出现「客户端设 1000，导入还是只进 10 首」。
	 *
	 * <p>（客户端设置页的按钮现在也会发同样的请求过来，效果一致；
	 * 这条指令是给「不想开界面」和「客户端的请求被权限拦下」时用的。）
	 */
	private static int setGlobalLimit(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		int value = IntegerArgumentType.getInteger(ctx, "limit");
		MusicConfig cfg = MusicConfig.get();
		int old = cfg.maxQueueSize;
		cfg.maxQueueSize = Math.max(1, Math.min(MusicConfig.MAX_QUEUE_LIMIT, value));
		cfg.save();

		src.sendFeedback(() -> Text.literal("[" + DISPLAY + "] 服务端队列上限已从 "
				+ old + " 改为 " + cfg.maxQueueSize + " 首")
				.formatted(Formatting.GREEN), true);
		src.sendFeedback(() -> Text.literal("  · 已写入服务端配置文件（重启后仍有效）")
				.formatted(Formatting.GRAY), false);
		src.sendFeedback(() -> Text.literal("  · 已单独设过上限的房间不受影响；"
				+ "要改单个房间用 /" + DISPLAY + " limit <房间名> <数字>")
				.formatted(Formatting.DARK_GRAY), false);
		for (String r : RoomManager.get().roomNames()) {
			YueMusic.LOGGER.info("[{}] 房间「{}」上限现在是 {} 首",
					YueMusic.MOD_NAME, r, RoomManager.get().queueFor(r).limit());
		}
		return cfg.maxQueueSize;
	}


	/**
	 * 清理所有「没人的空房间」，释放它们占用的内存。
	 *
	 * <p>说明：空房间默认会一直保留（这样歌不会丢）。这个指令只在你
	 * **主动想释放内存**时用（正常情况根本不需要 —— 实测 1000 首才约 290KB）。
	 *
	 * <p>清理前会先把队列写盘，所以**存档保留**；真有人再进那个房间时会自动载入。
	 */
	private static int roomCleanup(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		RoomManager rm = RoomManager.get();
		int before = rm.roomCount();
		int removed = rm.cleanupEmptyRooms();
		int after = rm.roomCount();
		src.sendFeedback(() -> Text.literal("[" + DISPLAY + "] 已清理 " + removed
				+ " 个空房间（" + before + " → " + after + "）")
				.formatted(removed > 0 ? Formatting.GREEN : Formatting.GRAY), true);
		src.sendFeedback(() -> Text.literal("  · 存档已保留，玩家再进该房间会自动载入")
				.formatted(Formatting.DARK_GRAY), false);
		return removed;
	}

	// ------------------------------------------------------------ 房间

	private static int roomJoin(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		var player = src.getPlayer();
		if (player == null) {
			src.sendError(Text.literal("[" + DISPLAY + "] 这条指令只能玩家使用"));
			return 0;
		}
		String want = StringArgumentType.getString(ctx, "name");
		String joined = RoomManager.get().join(player.getUuid(), player.getGameProfile().name(), want);
		MusicServer.syncRoom(player);
		src.sendFeedback(() -> Text.literal("[" + DISPLAY + "] 已进入房间「" + joined + "」（"
				+ RoomManager.get().playerCount(joined) + " 人）")
				.formatted(Formatting.GREEN), false);
		return 1;
	}

	private static int roomLeave(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		var player = src.getPlayer();
		if (player == null) {
			src.sendError(Text.literal("[" + DISPLAY + "] 这条指令只能玩家使用"));
			return 0;
		}
		RoomManager.get().leave(player.getUuid(), player.getGameProfile().name());
		MusicServer.syncRoom(player);
		src.sendFeedback(() -> Text.literal("[" + DISPLAY + "] 已回到默认房间")
				.formatted(Formatting.YELLOW), false);
		return 1;
	}

	private static int rooms(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		RoomManager rm = RoomManager.get();
		String mine = src.getPlayer() == null ? "" : rm.roomOf(src.getPlayer().getUuid());
		head(src);
		line(src, "房间总数：", rm.roomCount() + " 个", Formatting.WHITE);
		for (String r : rm.roomNames()) {
			String tag = r.equals(mine) ? " ← 你在这里" : "";
			src.sendFeedback(() -> Text.literal("  · " + r + "（" + rm.playerCount(r) + " 人，队列 "
					+ rm.queueFor(r).entries().size() + " 首）" + tag)
					.formatted(r.equals(mine) ? Formatting.GREEN : Formatting.GRAY), false);
		}
		src.sendFeedback(() -> Text.literal("切换：/" + DISPLAY + " room join <房间名>   "
				+ "回默认：/" + DISPLAY + " room leave").formatted(Formatting.YELLOW), false);
		return 1;
	}

	/** 是否是 OP（决定要不要显示服主账号名）。 */
	private static boolean isAdmin(ServerCommandSource src) {
		return src.getPlayer() != null && src.getPlayer().isCreativeLevelTwoOp();
	}

	private static int status(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();

		head(src);

		// ---- 账号登录状态：两个平台分开看（可以同时登录、同时生效）----
		boolean admin = isAdmin(src);
		com.yue.music.api.ScanLogin qq = com.yue.music.api.MusicApi.effectiveQqLogin();
		com.yue.music.api.ScanLogin ne = com.yue.music.api.MusicApi.neteaseLogin();
		boolean qqOn = qq != null && qq.loggedIn();
		boolean neOn = ne != null && ne.loggedIn();

		line(src, "QQ音乐：", qqOn ? "已登录 ✓" : "未登录 ✗",
				qqOn ? Formatting.GREEN : Formatting.RED);
		if (qqOn && admin) {
			String who = qq.nickname() == null || qq.nickname().isBlank()
					? qq.method().label() : qq.nickname();
			line(src, "  └ 账号：", who + "（" + qq.method().label() + "）", Formatting.WHITE);
		}

		line(src, "网易云：", neOn ? "已登录 ✓" : "未登录 ✗",
				neOn ? Formatting.GREEN : Formatting.RED);
		if (neOn && admin) {
			String who = ne.nickname() == null || ne.nickname().isBlank()
					? "(未知昵称)" : ne.nickname();
			line(src, "  └ 账号：", who, Formatting.WHITE);
		}

		if (qqOn || neOn) {
			line(src, "解析模式：", "服务端解析 —— 所有玩家免登录", Formatting.AQUA);
			if (qqOn && neOn) {
				line(src, "说明：", "两个平台各自用对应账号解析（QQ 走 QQ、网易云走网易云）",
						Formatting.DARK_AQUA);
			}
		} else {
			line(src, "解析模式：", "各客户端自己登录并解析", Formatting.GRAY);
			if (admin) {
				line(src, "开启方法：", "/" + DISPLAY + " login <浏览器Cookie>", Formatting.YELLOW);
				line(src, "  支持：", "QQ 的 qm_keyst / 网易云的 MUSIC_U（可分别登录）",
						Formatting.DARK_GRAY);
			}
		}

		ServerQueue q = ServerQueue.get();
		line(src, "队列：", q.entries().size() + " 首", Formatting.WHITE);
		var cur = q.current();
		if (cur != null) {
			line(src, "正在播放：", cur.track().display(), Formatting.WHITE);
			line(src, "点歌人：", cur.addedBy() == null || cur.addedBy().isEmpty()
					? "(未知)" : cur.addedBy(), Formatting.GRAY);
			// 直链来源按「当前这首歌属于哪个平台」来判断，比笼统的「服务端解析」准确
			String source = cur.track().source();
			boolean thisPlatformOn = "netease".equals(source) ? neOn : qqOn;
			line(src, "直链来源：", ServerLogin.get().loggedIn()
							? (thisPlatformOn ? "服务端解析（" + ("netease".equals(source) ? "网易云" : "QQ音乐") + " 账号）"
									: "服务端解析（该平台未登录，可能降级）")
							: "点歌客户端提供",
					Formatting.DARK_GRAY);
		} else {
			line(src, "正在播放：", "(空闲)", Formatting.GRAY);
		}
		return 1;
	}

	private static int login(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		String raw = StringArgumentType.getString(ctx, "cookie");

		src.sendFeedback(() -> Text.literal("[" + DISPLAY + "] 正在验证 Cookie（约需 3~10 秒）…")
				.formatted(Formatting.YELLOW), false);

		// 验证要联网，绝不能跑在服务端主线程上（否则全服卡 3~10 秒）。
		// 放到后台线程，结果再回到主线程播报。
		final net.minecraft.server.MinecraftServer mc = src.getServer();
		new Thread(() -> {
			String err = ServerLogin.get().loginWithCookie(raw);
			mc.execute(() -> {
				if (err != null) {
					src.sendError(Text.literal("[" + DISPLAY + "] 登录失败：" + err));
					return;
				}
				ServerLogin login = ServerLogin.get();
				String who = login.nick().isEmpty() ? ("uin " + login.uin()) : login.nick();
				src.sendFeedback(() -> Text.literal("[" + DISPLAY + "] 服务端登录成功：" + who
						+ " —— 现在所有玩家都免登录了 ✓").formatted(Formatting.GREEN), true);
			});
		}, "YueMusic-ServerLogin").start();
		return 1;
	}

	private static int logout(CommandContext<ServerCommandSource> ctx) {
		ServerCommandSource src = ctx.getSource();
		if (!ServerLogin.get().loggedIn()) {
			src.sendError(Text.literal("[" + DISPLAY + "] 服务端本来就没登录"));
			return 0;
		}
		ServerLogin.get().logout();
		src.sendFeedback(() -> Text.literal("[" + DISPLAY + "] 服务端已退出登录，"
				+ "回到「各客户端自己登录」模式").formatted(Formatting.YELLOW), true);
		return 1;
	}

	// ------------------------------------------------------------ 输出小工具

	private static void head(ServerCommandSource src) {
		src.sendFeedback(() -> Text.literal("──── SayoHonami_Music /" + DISPLAY + " ────")
				.formatted(Formatting.GOLD), false);
	}

	private static void line(ServerCommandSource src, String label, String value, Formatting color) {
		src.sendFeedback(() -> Text.literal(label).formatted(Formatting.GRAY)
				.append(Text.literal(value).formatted(color)), false);
	}

	/** 供 MusicServer 启动时调用。 */
	public static void init(CommandDispatcher<ServerCommandSource> dispatcher) {
		try {
			register(dispatcher);
			YueMusic.LOGGER.info("[{}] 已注册 /{} 指令（status / login / logout）",
					YueMusic.MOD_NAME, DISPLAY);
		} catch (Exception e) {
			YueMusic.LOGGER.error("[{}] 注册 /{} 指令失败", YueMusic.MOD_NAME, DISPLAY, e);
		}
	}
}
