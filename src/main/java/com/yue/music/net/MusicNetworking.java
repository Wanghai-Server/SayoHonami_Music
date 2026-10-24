package com.yue.music.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yue.music.YueMusic;
import com.yue.music.model.MusicTrack;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * 模组的自定义网络包（1.21.11 的 {@link CustomPayload}）。
 *
 * <p>设计要点：<b>服务端不联网、不解析音乐接口</b>。
 * 客户端搜索并解析出直链，把「歌 + 直链」报给服务端；服务端只维护队列和播放进度，
 * 再广播给所有装了模组的玩家。这样：
 * <ul>
 *   <li>服务器不需要访问外网音乐接口，不会被风控</li>
 *   <li>每台客户端自己下载音频，服务器不承担流量</li>
 *   <li>登录 Cookie 永远留在各自客户端，不上传</li>
 * </ul>
 */
public final class MusicNetworking {

	private MusicNetworking() {
	}

	/**
	 * 服务端解析失败时，在 {@code notice} 里带这个前缀通知点歌的客户端「你自己解析一下再发我」。
	 * <p>用于服主的 Cookie 过期/被风控时自动降级，避免全服一起放不了歌。
	 */
	public static final String NEED_CLIENT_RESOLVE = "\u0001RESOLVE:";

	/**
	 * 队列 JSON 允许的最大字符数。
	 *
	 * <p><b>为什么是这个值</b>：以前队列字段的上限是 {@code 32767}，
	 * 而 1000 首的歌单 JSON 约 18 万字符 —— 超过上限时服务端<b>编不出报文</b>：
	 * <pre>
	 *   EncoderException: Failed to encode packet (sayo-honami-music:queue)
	 *   Caused by: String too big (was 85423 characters, max 32767)
	 * </pre>
	 * 玩家就被踢下线，而且重连也会立刻再被踢（每次广播都崩）。
	 *
	 * <p>现在给到 400 万字符：10000 首约 172 万字符，仍有 2 倍余量。
	 * 另外超过 {@link #COMPRESS_THRESHOLD} 会自动 GZIP 压缩
	 * （歌单 JSON 压缩后通常只剩 1/10），进一步避免报文过大。
	 */
	public static final int MAX_JSON_CHARS = 4_000_000;

	/** 队列 JSON 超过这个长度就先压缩再发。 */
	private static final int COMPRESS_THRESHOLD = 16_000;

	/**
	 * 编码队列 JSON：太长就先 GZIP + Base64。
	 *
	 * @return 数组：[0]=要发的字符串，[1]=是否压缩过（"1"/"0"）
	 */
	public static Object[] encodeQueueField(String json) {
		if (json == null) return new Object[]{"", false};
		if (json.length() <= COMPRESS_THRESHOLD) return new Object[]{json, false};
		try {
			java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(json.length() / 4);
			try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos)) {
				gz.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			String b64 = java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
			YueMusic.LOGGER.info("[YueMusic] 队列 JSON {} 字符 → 压缩后 {} 字符",
					json.length(), b64.length());
			return new Object[]{b64, true};
		} catch (Exception e) {
			// 压缩失败就用原文（上限已经足够大，通常也能过）
			YueMusic.LOGGER.warn("[YueMusic] 队列 JSON 压缩失败，改用原文: {}", e.toString());
			return new Object[]{json, false};
		}
	}

	/** 解压 {@link #encodeQueueField} 压过的字符串。 */
	public static String decompress(String b64) {
		try {
			byte[] raw = java.util.Base64.getDecoder().decode(b64);
			try (java.util.zip.GZIPInputStream gz =
						 new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
				return new String(gz.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			}
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 队列 JSON 解压失败: {}", e.toString());
			return "[]";
		}
	}

	/** 播放动作。 */
	public enum Action {
		PLAY, PAUSE, RESUME, NEXT, PREV, STOP, CLEAR, SEEK,
		/** 轮换播放模式：顺序 → 单曲 → 随机。 */
		MODE,
		/** 设置统一音量，value = 0~100（服务端会广播给所有人并记住）。 */
		SET_VOLUME,
		/** 从队列删除一首，value = 下标 + 1（0 保留给「继续播放」语义）。 */
		REMOVE
	}

	// ---------------------------------------------------------------- C2S

	/**
	 * 客户端点歌：把解析好的歌 + 直链交给服务端。
	 *
	 * @param trackJson 歌曲 JSON
	 * @param url       音频直链（服务端只做域名白名单校验，不自己联网）
	 * @param duration  时长（秒）
	 */
	public record AddSongC2S(String trackJson, String url, int duration) implements CustomPayload {
		public static final CustomPayload.Id<AddSongC2S> ID =
				new CustomPayload.Id<>(Identifier.of(YueMusic.MOD_ID, "add_song"));
		public static final PacketCodec<RegistryByteBuf, AddSongC2S> CODEC = PacketCodec.tuple(
				PacketCodecs.string(32767), AddSongC2S::trackJson,
				PacketCodecs.string(8192), AddSongC2S::url,
				PacketCodecs.VAR_INT, AddSongC2S::duration,
				AddSongC2S::new);

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** 客户端控制（暂停/继续/切歌/清空/循环/跳转）。 */
	public record ControlC2S(Action action, int value) implements CustomPayload {
		public static final CustomPayload.Id<ControlC2S> ID =
				new CustomPayload.Id<>(Identifier.of(YueMusic.MOD_ID, "control"));
		public static final PacketCodec<RegistryByteBuf, ControlC2S> CODEC = PacketCodec.tuple(
				PacketCodecs.VAR_INT, p -> p.action().ordinal(),
				PacketCodecs.VAR_INT, ControlC2S::value,
				(a, v) -> new ControlC2S(Action.values()[Math.floorMod(a, Action.values().length)], v));

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * 客户端请求修改「服务端队列上限」。
	 *
	 * <p><b>为什么需要它</b>：队列上限由<b>服务端</b>判定（导入歌单、点歌都走服务端），
	 * 而模组配置是<b>两端各读各的文件</b> —— 客户端设置页那个按钮以前只改本机配置，
	 * 服务端完全不知道，于是「客户端设 1000，导入还是只进 10 首」。
	 *
	 * <p>现在客户端按钮会发这个包，服务端校验 OP 权限后真正改掉上限。
	 *
	 * @param limit 期望的新上限（1~10000，服务端会再夹一次）
	 */
	public record SetLimitC2S(int limit) implements CustomPayload {
		public static final CustomPayload.Id<SetLimitC2S> ID =
				new CustomPayload.Id<>(Identifier.of(YueMusic.MOD_ID, "set_limit"));
		public static final PacketCodec<RegistryByteBuf, SetLimitC2S> CODEC = PacketCodec.tuple(
				PacketCodecs.VAR_INT, SetLimitC2S::limit,
				SetLimitC2S::new);

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/**
	 * 客户端批量导入歌单：一次交给服务端多首歌。
	 *
	 * <p><b>为什么单独一个包</b>：歌单可能有上千首，
	 * 用 {@link AddSongC2S} 一首一首发既慢又容易触发限流。
	 * 这个包把「歌单里的 N 首」一次性交给服务端，直接进房间队列。
	 *
	 * <p>这里的 url 一律留空 —— 由服务端在**真正播到那首时**再解析，
	 * 否则导入 1000 首要现场解析 1000 次，既慢又会触发音乐平台风控。
	 *
	 * @param tracksJson 歌曲数组 JSON（和队列状态同一格式）
	 * @param playlistName 歌单名（用于提示）
	 */
	public record ImportPlaylistC2S(String tracksJson, String playlistName) implements CustomPayload {
		public static final CustomPayload.Id<ImportPlaylistC2S> ID =
				new CustomPayload.Id<>(Identifier.of(YueMusic.MOD_ID, "import_playlist"));
		public static final PacketCodec<RegistryByteBuf, ImportPlaylistC2S> CODEC = PacketCodec.tuple(
				// 1000 首的 JSON 可能到几百 KB，这里给足上限
				PacketCodecs.string(2_000_000), ImportPlaylistC2S::tracksJson,
				PacketCodecs.string(256), p -> p.playlistName() == null ? "" : p.playlistName(),
				ImportPlaylistC2S::new);

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** 把歌曲列表编码成 JSON 数组（歌单导入用）。 */
	public static String encodeTracksJson(List<MusicTrack> tracks) {
		return encodeTracks(tracks);
	}

	/** 解析歌曲数组 JSON（和队列状态同一格式）。 */
	public static List<MusicTrack> decodeTracksJson(String json) {
		List<MusicTrack> out = new ArrayList<>();
		try {
			JsonElement el = JsonParser.parseString(json);
			if (!el.isJsonArray()) return out;
			for (JsonElement e : el.getAsJsonArray()) {
				if (e.isJsonObject()) out.add(MusicTrack.fromJson(e.getAsJsonObject()));
			}
		} catch (Exception e) {
			YueMusic.LOGGER.warn("[YueMusic] 歌曲列表解析失败: {}", e.toString());
		}
		return out;
	}

	/**
	 * 客户端房间操作：加入 / 离开 / 查询。
	 *
	 * @param action 动作
	 * @param room   房间名（leave/list 时可空）
	 */
	public record RoomC2S(RoomAction action, String room) implements CustomPayload {
		public static final CustomPayload.Id<RoomC2S> ID =
				new CustomPayload.Id<>(Identifier.of(YueMusic.MOD_ID, "room"));
		public static final PacketCodec<RegistryByteBuf, RoomC2S> CODEC = PacketCodec.tuple(
				PacketCodecs.VAR_INT, p -> p.action().ordinal(),
				PacketCodecs.string(32), p -> p.room() == null ? "" : p.room(),
				(a, r) -> new RoomC2S(RoomAction.values()[Math.floorMod(a, RoomAction.values().length)], r));

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	/** 房间动作。 */
	public enum RoomAction {
		/** 加入（不存在则创建） */
		JOIN,
		/** 回到默认房间 */
		LEAVE,
		/** 请求刷新当前房间信息（列表用聊天栏输出） */
		LIST
	}

	/** 客户端请求完整状态（刚进服时用）。 */	public record RequestSyncC2S() implements CustomPayload {
		public static final CustomPayload.Id<RequestSyncC2S> ID =
				new CustomPayload.Id<>(Identifier.of(YueMusic.MOD_ID, "request_sync"));
		public static final PacketCodec<RegistryByteBuf, RequestSyncC2S> CODEC =
				PacketCodec.unit(new RequestSyncC2S());

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}
	}

	// ---------------------------------------------------------------- S2C

	/**
	 * 服务端广播的队列状态。
	 *
	 * @param tracksJson    队列 JSON
	 * @param currentIndex  正在播放的下标，-1 表示空闲
	 * @param playing       是否在播放
	 * @param startEpochMs  开始播放的绝对时间（毫秒），客户端据此对齐进度
	 * @param playMode      播放模式：SEQUENTIAL / SINGLE / SHUFFLE
	 * @param volume        统一音量 0~100
	 * @param notice        提示文本（点歌成功/失败）
	 */
	public record QueueS2C(String tracksJson, int currentIndex, boolean playing,
						   long startEpochMs, String playMode, int volume,
						   String notice, boolean serverLoggedIn, String room,
						   long positionMs, int serverLimit,
						   boolean compressed) implements CustomPayload {

		public static final CustomPayload.Id<QueueS2C> ID =
				new CustomPayload.Id<>(Identifier.of(YueMusic.MOD_ID, "queue"));
		public static final PacketCodec<RegistryByteBuf, QueueS2C> CODEC = PacketCodec.tuple(
				// 以前这里是 32767 —— 队列超过约 190 首就会
				// "String too big (was 85423 characters, max 32767)"，
				// 报文编不出来 → 玩家被踢下线。现在给足上限（10000 首的 JSON 约 172 万字符）。
				PacketCodecs.string(MAX_JSON_CHARS), QueueS2C::tracksJson,
				PacketCodecs.VAR_INT, QueueS2C::currentIndex,
				PacketCodecs.BOOLEAN, QueueS2C::playing,
				PacketCodecs.VAR_LONG, QueueS2C::startEpochMs,
				PacketCodecs.string(16), q -> q.playMode() == null ? "SEQUENTIAL" : q.playMode(),
				PacketCodecs.VAR_INT, QueueS2C::volume,
				PacketCodecs.string(1024), q -> q.notice() == null ? "" : q.notice(),
				PacketCodecs.BOOLEAN, QueueS2C::serverLoggedIn,
				PacketCodecs.string(32), q -> q.room() == null ? "default" : q.room(),
				PacketCodecs.VAR_LONG, QueueS2C::positionMs,
				PacketCodecs.VAR_INT, QueueS2C::serverLimit,
				PacketCodecs.BOOLEAN, QueueS2C::compressed,
				QueueS2C::new);

		/** 取出队列 JSON（自动解压）。 */
		public String tracksJsonDecoded() {
			return compressed ? decompress(tracksJson) : tracksJson;
		}

		@Override
		public CustomPayload.Id<? extends CustomPayload> getId() {
			return ID;
		}

		/** 解析歌曲列表（自动处理压缩过的 JSON）。 */
		public List<MusicTrack> tracks() {
			List<MusicTrack> out = new ArrayList<>();
			if (tracksJson == null || tracksJson.isBlank()) return out;
			try {
				// 队列超过上百首时服务端会先 GZIP 压缩再发，这里要先解压
				String json = compressed ? decompress(tracksJson) : tracksJson;
				JsonElement el = JsonParser.parseString(json);
				if (!el.isJsonArray()) return out;
				for (JsonElement e : el.getAsJsonArray()) {
					if (e.isJsonObject()) out.add(MusicTrack.fromJson(e.getAsJsonObject()));
				}
			} catch (Exception e) {
				YueMusic.LOGGER.warn("[YueMusic] 队列解析失败: {}", e.toString());
			}
			return out;
		}
	}

	/** 把歌曲列表编码成 JSON。 */
	public static String encodeTracks(List<MusicTrack> tracks) {
		JsonArray arr = new JsonArray();
		for (MusicTrack t : tracks) {
			JsonObject o = t.toJson();
			arr.add(o);
		}
		return arr.toString();
	}

	/**
	 * 编码「歌曲 + 服务端已解析好的直链」。
	 *
	 * <p><b>为什么要带上 url</b>：服务端登录后，直链是**服务端用服主账号解析**出来的。
	 * 如果广播里只发歌名，客户端就得各自再解析一次 —— 而其他玩家根本没登录，
	 * 解析必然失败，表现就是「A 点了歌，B 听不到」。
	 * 把直链一起广播出去，客户端直接下载即可，**完全不需要登录**，
	 * 这才是「服务端登录」真正该有的效果。
	 *
	 * <p>没登录的服务器 url 是空串，客户端会退回自己解析（旧行为）。
	 */
	public static String encodeQueue(List<com.yue.music.server.QueueEntry> entries) {
		JsonArray arr = new JsonArray();
		for (com.yue.music.server.QueueEntry e : entries) {
			JsonObject o = e.track().toJson();
			if (e.url() != null && !e.url().isBlank()) {
				o.addProperty("_streamUrl", e.url());
			}
			arr.add(o);
		}
		return arr.toString();
	}
	/** 从广播 JSON 里取出第 index 首的服务端直链（没有返回 null）。 */
	public static String streamUrlAt(String tracksJson, int index) {
		try {
			JsonElement el = JsonParser.parseString(tracksJson);
			if (!el.isJsonArray()) return null;
			JsonArray arr = el.getAsJsonArray();
			if (index < 0 || index >= arr.size()) return null;
			JsonElement e = arr.get(index);
			if (!e.isJsonObject()) return null;
			JsonObject o = e.getAsJsonObject();
			if (o.has("_streamUrl") && !o.get("_streamUrl").isJsonNull()) {
				String u = o.get("_streamUrl").getAsString();
				return u.isBlank() ? null : u;
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	/** 单首歌编码。 */
	public static String encodeTrack(MusicTrack t) {
		return t.toJson().toString();
	}

	public static MusicTrack decodeTrack(String json) {
		return MusicTrack.fromJson(JsonParser.parseString(json).getAsJsonObject());
	}
}
