// zxing-wasm 后台解码 worker（引擎：ZXing C++ 编译的 WASM，同目录 reader.js + zxing_reader.wasm）
// 把解码挪出主线程（对应 BGAQRCode「子线程解码」的做法），页面不卡、识别也快得多
importScripts('./reader.js');
let initErr = '';
// locateFile 默认指向 jsDelivr CDN，这里改回本地同目录，保证离线可用
ZXingWASM.prepareZXingModule({
  overrides: { locateFile: (f) => new URL(f, self.location.href).href },
  fireImmediately: true
}).then(() => {
  postMessage({ ready: true });
}).catch((e) => {
  initErr = String((e && (e.message || e)) || 'WASM 初始化失败');
  postMessage({ ready: false, err: initErr });
});
self.onmessage = async (ev) => {
  const d = ev.data || {};
  if (d.id == null) return;
  if (initErr) { postMessage({ id: d.id, text: '', err: initErr }); return; }
  try {
    const img = { data: new Uint8ClampedArray(d.buf), width: d.w, height: d.h };
    const res = await ZXingWASM.readBarcodes(img, {
      formats: ['AllLinear'], // 本页只扫一维条码，不必浪费时间去试二维码
      tryHarder: true,
      tryRotate: false,       // 条码不会横过来贴；旋转/反色/降尺度三重尝试是主要的耗时来源
      tryInvert: false,
      tryDownscale: false
    });
    postMessage({ id: d.id, text: res && res[0] ? String(res[0].text || '').trim() : '' });
  } catch (e) {
    postMessage({ id: d.id, text: '' }); // 解不出码是常态，由主线程下一轮再试
  }
};
