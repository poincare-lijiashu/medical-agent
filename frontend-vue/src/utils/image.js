// 前端图片压缩：逐字迁移 legacy util.js compressImage（与后端 img_utils.normalize_data_url
// 同策略：Canvas 长边 ≤2000 + JPEG 质量递减至 ≤6MB）。非图/解码失败原样返回 data URL，
// 交后端兜底丢弃。
const IMG_MAX_EDGE = 2000
const IMG_MAX_BYTES = 6 * 1024 * 1024
const IMG_Q_STEPS = [0.9, 0.8, 0.7, 0.6, 0.5, 0.4]

export function compressImage(file) {
  return new Promise((res) => {
    const rd = new FileReader()
    rd.onload = () => {
      const src = String(rd.result)
      const img = new Image()
      img.onload = () => {
        try {
          // 已合规（长边 ≤2000 且文件 ≤6MB）→ 原样返回，避免无谓重编码损伤画质
          if (Math.max(img.width, img.height) <= IMG_MAX_EDGE && file.size <= IMG_MAX_BYTES) {
            res(src)
            return
          }
          const scale = Math.min(1, IMG_MAX_EDGE / Math.max(img.width, img.height))
          const cv = document.createElement('canvas')
          cv.width = Math.max(1, Math.round(img.width * scale))
          cv.height = Math.max(1, Math.round(img.height * scale))
          cv.getContext('2d').drawImage(img, 0, 0, cv.width, cv.height)
          let out = cv.toDataURL('image/jpeg', 0.9)
          for (const q of IMG_Q_STEPS) {
            out = cv.toDataURL('image/jpeg', q)
            const bytes = (out.length - out.indexOf(',') - 1) * 0.75 // base64 长度换算回字节
            if (bytes <= IMG_MAX_BYTES) break
          }
          res(out)
        } catch (_) {
          res(src) // 压缩链路任何异常：原样返回（后端兜底）
        }
      }
      img.onerror = () => res(src)
      img.src = src
    }
    rd.onerror = () => res('')
    rd.readAsDataURL(file)
  })
}

// 多图选择公共入列（legacy imaging/consult 同策略：上限 10 张，超出 toast 忽略，
// 压缩后追加；返回 null 表示全部被忽略）。MAX_IMG 与 legacy MAX_IMG 同值。
export const MAX_IMG = 10

export async function appendImages(cur, files, toastIgnored) {
  const fs = [...files]
  if (!fs.length) return cur
  const add = fs.slice(0, MAX_IMG - cur.length)
  if (add.length < fs.length) toastIgnored(`最多 ${MAX_IMG} 张，已忽略超出部分`)
  if (!add.length) return cur
  const imgs = (await Promise.all(add.map((f) => compressImage(f)))).filter(Boolean)
  return cur.concat(imgs).slice(0, MAX_IMG)
}
