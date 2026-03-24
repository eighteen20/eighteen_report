/**
 * 第三方库类型声明补充（本项目本地声明）。
 *
 * 说明：
 * - `qrcode` 与 `bwip-js` 在当前版本下未自动携带可被 vue-tsc 直接识别的类型定义；
 * - 为保证类型检查通过，这里先提供最小化模块声明；
 * - 业务代码中对这两个库的调用已限定在预览渲染场景。
 */

/**
 * qrcode 模块声明（最小可用）。
 */
declare module 'qrcode' {
  const QRCode: {
    /**
     * 将文本渲染到 Canvas。
     */
    toCanvas: (
      canvas: HTMLCanvasElement,
      text: string,
      options?: Record<string, unknown>,
    ) => Promise<void>
  }
  export default QRCode
}

/**
 * bwip-js 模块声明（最小可用）。
 */
declare module 'bwip-js' {
  const bwipjs: {
    /**
     * 将条形码绘制到 Canvas。
     */
    toCanvas: (
      canvas: HTMLCanvasElement,
      options: Record<string, unknown>,
    ) => void
  }
  export default bwipjs
}
