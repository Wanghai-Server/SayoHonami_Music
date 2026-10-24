import com.yue.music.api.ScanLogin;
import com.yue.music.api.WeChatLogin;
import com.yue.music.api.QQLogin;

/** 实测微信扫码登录链路（不完成登录，只验证二维码与轮询接口）。 */
public class WeChatTest {

	static int pass = 0, fail = 0;

	static void check(String name, boolean ok, String detail) {
		if (ok) {
			pass++;
			System.out.println("[PASS] " + name + "  " + detail);
		} else {
			fail++;
			System.out.println("[FAIL] " + name + "  " + detail);
		}
	}

	public static void main(String[] args) throws Exception {
		System.out.println("===== 微信扫码登录链路 =====");
		WeChatLogin wx = new WeChatLogin();
		check("登录方式标识", wx.method() == ScanLogin.Method.WECHAT,
				"method=" + wx.method() + " label=" + wx.method().label() + " loginType=" + wx.loginType());

		byte[] img = wx.fetchQrCode();
		boolean isJpeg = img != null && img.length > 1000
				&& (img[0] & 0xFF) == 0xFF && (img[1] & 0xFF) == 0xD8;
		check("拿到微信二维码图片", isJpeg,
				img == null ? "null" : img.length + " 字节（JPEG 头校验=" + isJpeg + "）uuid=" + wx.uuid());
		check("二维码是 JPEG（MC 的 NativeImage 支持）", isJpeg, "FF D8 开头 = JPEG");

		if (isJpeg) {
			// 轮询 2 次，应该是 404（等待扫码）
			for (int i = 0; i < 2; i++) {
				wx.pollOnce();
				System.out.println("  第 " + (i + 1) + " 次轮询: state=" + wx.state() + " msg=" + wx.message());
			}
			check("轮询返回正常状态", wx.state() == ScanLogin.State.WAITING || wx.state() == ScanLogin.State.SCANNED,
					"state=" + wx.state());
			check("二维码可跨多次轮询复用", wx.uuid() != null && !wx.uuid().isEmpty(), "uuid=" + wx.uuid());
		}

		System.out.println("\n===== QQ 登录回归（确认重构没弄坏）=====");
		QQLogin qq = new QQLogin();
		check("QQ 登录方式标识", qq.method() == ScanLogin.Method.QQ,
				"method=" + qq.method() + " loginType=" + qq.loginType());
		byte[] qr = qq.fetchQrCode();
		boolean isPng = qr != null && qr.length > 100
				&& (qr[0] & 0xFF) == 0x89 && qr[1] == 'P';
		check("QQ 二维码仍正常", isPng, qr == null ? "null" : qr.length + " 字节");
		if (isPng) {
			qq.pollOnce();
			check("QQ 轮询仍正常", qq.state() == ScanLogin.State.WAITING,
					"state=" + qq.state() + " msg=" + qq.message());
		}

		System.out.println("\n===== Cookie 隔离（两种登录各存一份）=====");
		// 获取二维码时会产生会话 cookie（qrsig / uuid 相关），这是正常的；
		// 真正要隔离的是「登录凭证」——未登录时不应该有 qm_keyst
		boolean noKey = !qq.cookieMap().containsKey("qm_keyst") && !wx.cookieMap().containsKey("qm_keyst");
		check("未登录时没有登录凭证 qm_keyst", noKey,
				"QQ cookies=" + qq.cookieMap().keySet() + " / 微信 cookies=" + wx.cookieMap().keySet());
		check("未登录时 uin 为 0", "0".equals(qq.uin()) && "0".equals(wx.uin()), "uin=" + qq.uin());

		System.out.println("\n================ 结果: " + pass + " 通过 / " + fail + " 失败 ================");
	}
}
