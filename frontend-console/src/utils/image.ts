/**
 * 浏览器端影像压缩（第 199 批，从旧前端 `utils/image.js` 迁移）。
 *
 * 与后端 `backend/core/img_utils.normalize_data_url` 同策略：长边 ≤2000，
 * 逐档降低 JPEG 质量直到 ≤6MB；已合规的图片原样返回，避免无谓重编码。
 *
 * 后端会对 data URL 再做一次 SSRF 收口（只接受 `data:image/`）；前端这层
 * 只负责**减少传输体积**，不作为安全边界。
 */
const IMG_MAX_EDGE = 2000
const IMG_MAX_BYTES = 6 * 1024 * 1024
const QUALITY_STEPS = [0.9, 0.8, 0.7, 0.6, 0.5, 0.4]

/** 与后端 `AskReq.images` 的张数上限同口径。 */
export const MAX_IMG = 10

export function compressImage(file: File): Promise<string> {
  return new Promise((resolve) => {
    const reader = new FileReader()
    reader.onload = () => {
      const source = String(reader.result ?? '')
      const image = new Image()
      image.onload = () => {
        try {
          if (Math.max(image.width, image.height) <= IMG_MAX_EDGE && file.size <= IMG_MAX_BYTES) {
            resolve(source)
            return
          }
          const scale = Math.min(1, IMG_MAX_EDGE / Math.max(image.width, image.height))
          const canvas = document.createElement('canvas')
          canvas.width = Math.max(1, Math.round(image.width * scale))
          canvas.height = Math.max(1, Math.round(image.height * scale))
          const ctx = canvas.getContext('2d')
          if (!ctx) {
            resolve(source)
            return
          }
          ctx.drawImage(image, 0, 0, canvas.width, canvas.height)
          let out = canvas.toDataURL('image/jpeg', 0.9)
          for (const quality of QUALITY_STEPS) {
            out = canvas.toDataURL('image/jpeg', quality)
            // base64 长度换算回字节：4 字符 ≈ 3 字节
            const bytes = (out.length - out.indexOf(',') - 1) * 0.75
            if (bytes <= IMG_MAX_BYTES) break
          }
          resolve(out)
        } catch {
          resolve(source) // 压缩链路异常：原样返回，交后端兜底
        }
      }
      image.onerror = () => resolve(source)
      image.src = source
    }
    reader.onerror = () => resolve('')
    reader.readAsDataURL(file)
  })
}

export interface AppendImagesResult {
  images: string[]
  added: number
  /** 因超过 10 张被忽略的数量（必须显式告知用户） */
  overflow: number
  /** 非图片文件数量 */
  rejected: number
}

/** 多图累加：分次选择追加，先压缩再入列，超过上限的显式回传数量。 */
export async function appendImages(current: string[], files: File[]): Promise<AppendImagesResult> {
  const images = files.filter((f) => f.type.startsWith('image/'))
  const rejected = files.length - images.length
  const room = Math.max(0, MAX_IMG - current.length)
  const picked = images.slice(0, room)
  const overflow = images.length - picked.length
  if (!picked.length) return { images: current, added: 0, overflow, rejected }
  const encoded = (await Promise.all(picked.map((f) => compressImage(f)))).filter(Boolean)
  return {
    images: [...current, ...encoded].slice(0, MAX_IMG),
    added: encoded.length,
    overflow,
    rejected,
  }
}
