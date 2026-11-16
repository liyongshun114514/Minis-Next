# 模型品牌图标离线资产（`assets/model-icons/`）

本文档记录 Minis Next 模型选择器所用品牌图标的来源、许可、目录结构、生成流程与校验结果。资产本身位于
`src/android/app/src/main/assets/model-icons/`，随 APK 打包（`assets` 目录）。

## 1. 范围

| 项 | 内容 |
|---|---|
| 新增目录 | `src/android/app/src/main/assets/model-icons/`（PNG + `catalog.json` + 许可原文） |
| 新增文档 | `docs/model-icons.md`（本文件） |
| 未改动 | 任何 Kotlin / Gradle / 全局配置 / 现有 `THIRD_PARTY_LICENSES.md`；模型自动匹配逻辑由另一任务实现 |

设计前提：Android 侧仅依赖 `coil-compose`（**无** SVG 解码器，未引入 `coil-svg`），因此本目录**只提供光栅 PNG**，
不提供运行时 SVG 解码；SVG 仅作为中间产物（源文件），不进入 `assets`，避免同一图标的矢量与位图双份膨胀。

## 2. 上游来源（固定版本）

| 项 | 值 |
|---|---|
| 上游站点 | <https://lobehub.com/zh/icons> |
| 上游仓库 | <https://github.com/lobehub/lobe-icons> |
| npm 包 | [`@lobehub/icons`](https://www.npmjs.com/package/@lobehub/icons) |
| **固定版本** | **6.15.2** |
| tarball URL | <https://registry.npmjs.org/@lobehub/icons/-/icons-6.15.2.tgz> |
| tarball SHA-256 | `19906e3368d91f810f35b0da995899e5e4eebbd05b1b50e71ad9ab16f1d18588` |
| 上游许可 | **MIT**（`LICENSE`，Copyright (c) 2023 LobeHub） |
| 抓取时间 | 2026-11-16（Asia/Shanghai） |

上游 SVG 在 npm 包中以 React 组件形式发布（`es/<Brand>/components/{Mono,Color}.mjs`，即 `react/jsx-runtime`
的 `jsx()` 调用树），包内**不含** `.svg` 文件；因此本资产由这些组件源码逐字还原为 SVG 后光栅化，未重绘、未改形状。
取色与图形均来自上游 6.15.2 的原始路径数据。

## 3. 许可

- 上游代码与图标资源整体以 **MIT** 授权，许可原文逐字保留于
  [`assets/model-icons/LICENSE-lobehub-icons.txt`](../src/android/app/src/main/assets/model-icons/LICENSE-lobehub-icons.txt)。
- **商标提示**：MIT 授权的是上游仓库中的图形文件/代码，不构成对各品牌商标的授权。OpenAI、Claude、Gemini、Qwen、
  Zhipu、MiniMax 等名称与标识均为其各自权利人的商标；本资产仅用于「指明所配置的模型/服务商」这一识别性用途，
  不作背书暗示。若某品牌权利方提出异议，删除对应 PNG 与 `catalog.json` 条目即可，不影响其余图标。
- 上游 6.15.2 未对单个图标附加额外条款；如上游后续对个别品牌图标改用更严格许可，需按新版本重新核对后方可升级。

## 4. 目录内容与 `catalog.json` 结构

```
src/android/app/src/main/assets/model-icons/
├── catalog.json                  # 索引，42 条
├── LICENSE-lobehub-icons.txt     # 上游 MIT 许可原文（逐字）
└── *.png                         # 42 个 192×192 RGBA PNG，透明背景
```

`catalog.json` 为 JSON 数组，元素结构：

```json
{
  "id": "openai",              // 规范 id，全小写、无分隔符；建议作为匹配与展示的主键
  "name": "OpenAI",            // 展示名，取自上游 TITLE（HuggingFace 等按品牌习惯写法微调）
  "aliases": ["gpt", "chatgpt", "gpt-4o"],   // 别名，含 models.dev provider id / 常见模型前缀
  "file": "openai.png"         // 相对本目录的文件名（不含任何路径前缀）
}
```

- `file` 一律为相对 `model-icons/` 的文件名，可直接 `assets.open("model-icons/" + file)` /
  `"file:///android_asset/model-icons/" + file` 交给 Coil。
- `id` 唯一，`file` 唯一，二者一一对应（已验证）。
- `aliases` 已对齐 `assets/models-dev-api.json` 中的 provider id（如 `github-copilot`、`amazon-bedrock`、
  `google-vertex`、`siliconflow`、`moonshotai`、`zhipuai`、`zai`、`togetherai`、`fireworks-ai`、`xiaomi` 等），
  便于自动匹配代理直接做「provider id → alias 命中」的映射。

## 5. 图标清单（42 个）

选取原则：覆盖主流闭源/开源模型家族与常见聚合/本地推理服务商；同族只保留辨识度最高者，避免同义重复。

| `id` | 上游组件目录（6.15.2） | 渲染变体 |
|---|---|---|
| `openai` | `OpenAI` | Mono (dark) |
| `anthropic` | `Anthropic` | Mono (dark) |
| `claude` | `Claude` | Color |
| `deepseek` | `DeepSeek` | Color |
| `gemini` | `Gemini` | Color |
| `google` | `Google` | Color |
| `qwen` | `Qwen` | Color |
| `moonshot` | `Moonshot` | Mono (dark) |
| `kimi` | `Kimi` | Color |
| `zhipu` | `Zhipu` | Color |
| `zai` | `ZAI` | Mono (dark) |
| `minimax` | `Minimax` | Color |
| `doubao` | `Doubao` | Color |
| `meta` | `Meta` | Color |
| `mistral` | `Mistral` | Color |
| `grok` | `Grok` | Mono (dark) |
| `xai` | `XAI` | Mono (dark) |
| `cohere` | `Cohere` | Color |
| `perplexity` | `Perplexity` | Color |
| `huggingface` | `HuggingFace` | Color |
| `ollama` | `Ollama` | Mono (dark) |
| `openrouter` | `OpenRouter` | Color |
| `github` | `Github` | Mono (dark) |
| `copilot` | `Copilot` | Color |
| `groq` | `Groq` | Mono (dark) |
| `cerebras` | `Cerebras` | Color |
| `together` | `Together` | Color |
| `fireworks` | `Fireworks` | Color |
| `deepinfra` | `DeepInfra` | Color |
| `nvidia` | `Nvidia` | Color |
| `bedrock` | `Bedrock` | Mono (dark) |
| `azure` | `Azure` | Color |
| `vertexai` | `VertexAI` | Color |
| `openwebui` | `OpenWebUI` | Mono (dark) |
| `lmstudio` | `LmStudio` | Mono (dark) |
| `vllm` | `Vllm` | Color |
| `siliconcloud` | `SiliconCloud` | Color |
| `volcengine` | `Volcengine` | Color |
| `baidu` | `Baidu` | Color |
| `tencent` | `Tencent` | Color |
| `huawei` | `Huawei` | Color |
| `xiaomi` | `XiaomiMiMo` | Mono (dark) |

说明：

- `Color`：直接采用上游彩色版本（保留渐变/多色填充，`id` 已在单文件内唯一，不会跨图标冲突）。
- `Mono (dark)`：上游仅有单色版本，绘制时将 `currentColor` 统一替换为 `#1A1A1A`（深色），以便在当前 UI（浅色卡片）上直接可见；
  该类图标不适合在纯黑背景上原样使用，若后续接入深色主题，需按需对其做 `ColorFilter` 反相或另出浅色变体。
- 部分彩色图标（如 Claude、Gemini、Cohere、Perplexity）本身含大面积留白，`填充比` 一列偏低属正常。

## 6. 校验记录（42/42）

校验脚本对每个条目断言：`catalog.json` 可解析、`id` 唯一、`file` 均存在且与目录内 PNG 集合完全一致、
PNG 由 PIL 完整解码、尺寸 192×192、色彩模式 RGBA、Alpha 通道非空（非纯透明）、不透明像素占比 > 3%、
四角为透明（背景透明）。全部通过。

汇总：`icons=42  total_png_bytes=277327  catalog_bytes=7387  catalog_sha256=d2edd81638c744b6385727c023b3255cf4f5c056f6d9d2633818bd548d18c4d3`

索引在上游名称之外补充了中文常用别名（如通义千问、豆包、智谱、文心、混元、硅基流动），PNG 字节与下表一致。

| `id` | `name` | PNG 字节 | 不透明占比 | 尺寸 | SHA-256 |
|---|---|---:|---:|---|---|
| `openai` | OpenAI | 12224 | 39% | 192×192 | 45dd67c5bb90e90dc07354bff07ac60d2745b6b15ae688a7bed55fa5108cba8e |
| `anthropic` | Anthropic | 4882 | 33% | 192×192 | 6fe076e0fa81a55987c6acbcdd1eff21e7b2724537fd7122e81e18950e029d9e |
| `claude` | Claude | 11330 | 41% | 192×192 | ce2a4366ea90407d65a6ec986dbbb6c451ad51c88567608b5ab11c2965d3ddb7 |
| `deepseek` | DeepSeek | 7528 | 38% | 192×192 | e62bb0c72a1fe8ea1e1056c491d1d68a54a992be0e88fa297375da64bdbf58c5 |
| `gemini` | Gemini | 3148 | 30% | 192×192 | ac26ec17de14cb290e678fb8ab3d1c94fe5575c5e26d6f5e2c29e768fa94858d |
| `google` | Google | 6833 | 43% | 192×192 | 6a917ff37d792e136e8dd53cd94184ee68d754d8f3a630b929646ca35c4a0767 |
| `qwen` | Qwen | 5822 | 51% | 192×192 | cb10b681db771fa4426666e6ba66515f59f873e7c76d2cb751c824f9f757f9e7 |
| `moonshot` | Moonshot AI | 7740 | 55% | 192×192 | 2450b9fd61dd88241d5d55901801332155c02f73758680605e05616ebd18f7d8 |
| `kimi` | Kimi | 3033 | 26% | 192×192 | 0f897d8883508e419fc68739ca53154ea0f6f3b4577822d5c9b28d2ca01b0176 |
| `zhipu` | Zhipu AI | 7969 | 19% | 192×192 | 07fafcb0535daed45c3803446a2f5bfdddaccbce65f66443ac1f45c88d1c1e09 |
| `zai` | Z.AI | 3385 | 42% | 192×192 | c228ac291dac059c76769e443ad497c321bbff9a189cb0c76fccea2a16d65949 |
| `minimax` | MiniMax | 3518 | 29% | 192×192 | d38b814abc12962b6f7bec7a8d7b332e664bde670ada921f45e2c316ac296248 |
| `doubao` | Doubao | 7598 | 47% | 192×192 | 435cf971fa1006785d3dc4a1521ef900f1021a8bba360300738670bd77ac5443 |
| `meta` | Meta | 6974 | 31% | 192×192 | 9baf9e9c8959c7f123f77538a395aa25b538ad8ac24379040d58114a7f8f712b |
| `mistral` | Mistral AI | 977 | 42% | 192×192 | cfe4017be5dcdea513802e8e1fbdcf7ada08905c1f578f908d90f5a8108f2063 |
| `grok` | Grok | 7573 | 24% | 192×192 | ae56bf14f0196b73b9173378da33c4fe1b776848b35a5026897178a28c83e2c3 |
| `xai` | xAI | 4853 | 37% | 192×192 | 521339c7d8cb96d47fa9542fbe193ee671c4a44e07c77449445d97ec0d0a25ea |
| `cohere` | Cohere | 5952 | 66% | 192×192 | ae17ccb7b5d94185a4564a364e17748aa0c04a72ef456d0652936fbc771ad6c3 |
| `perplexity` | Perplexity | 7308 | 30% | 192×192 | be03fefd5ed46aac5f28093c74c6bf7edd539b1ba8b082e58ea0defa1e01d1ae |
| `huggingface` | Hugging Face | 11216 | 61% | 192×192 | 90f67b14bd5b96e4174868b2b08946814de1dfd24307c7362b4cd3dec351921d |
| `ollama` | Ollama | 8984 | 20% | 192×192 | 0d6f765fef15fb5b638563d92560438ce5013297df59f4897ed8123055297ad4 |
| `openrouter` | OpenRouter | 4202 | 47% | 192×192 | c32b19060e9625cdbf35b3bc891f404d6956ec472df79e4d786db54326cdf9aa |
| `github` | GitHub | 7221 | 43% | 192×192 | 4306f05e804542c7242d7c8b0a47fd9b49911bb2fc3210936dfc406e7859657e |
| `copilot` | GitHub Copilot | 3674 | 61% | 192×192 | 3d8cc7bd6ed1b17c2aa70bcdd28500fc554586e9529f7af86523b624b1656138 |
| `groq` | Groq | 4867 | 22% | 192×192 | 33b79542827f674e187f452ddb9c8c7359533a5200423fcb9fa8b1bf277f89dc |
| `cerebras` | Cerebras | 10334 | 25% | 192×192 | 9688d14a33168564e46c9c30340ed98dcb43f92dfd666d066b8fbd7a2734b47f |
| `together` | Together AI | 7525 | 58% | 192×192 | 8ac520bfccf1f486ac25cf9cebde6390327fa80683dd448faf7829b9e2e5975a |
| `fireworks` | Fireworks AI | 5373 | 16% | 192×192 | 77dad0014bb0454b9bb8d4be364fa47406641d07fda42130448bb4016a2bd8af |
| `deepinfra` | DeepInfra | 16713 | 29% | 192×192 | 63fbe06cc5ba45df73404a6d05204a211ec0649cc0baecc4fd558ef424d05772 |
| `nvidia` | NVIDIA | 7043 | 34% | 192×192 | 95153da4a6e573130d249e7ba5ed2872e2158441b9287f62a5405ac28b81ab9c |
| `bedrock` | AWS Bedrock | 9625 | 21% | 192×192 | 7a761946f51eb78ad610aad1344a30f96c2a824b1d69f26667dfb3d4cbf73f98 |
| `azure` | Azure OpenAI | 3516 | 62% | 192×192 | 42a7886b2b3ca4fe80d32fde5864f2d9f185ccf1446980540025d4653e2624d0 |
| `vertexai` | Vertex AI | 10601 | 24% | 192×192 | cdba60f3a3a802ed19174dc03176d509eaca334b508c8d32b0a292fe7dfa4094 |
| `openwebui` | Open WebUI | 4945 | 40% | 192×192 | 52674041f625e27bebfdbfeee24bd54504ff809e2e9b621337b4c2dcbad8a64f |
| `lmstudio` | LM Studio | 4260 | 42% | 192×192 | 6fae18a4d7bbc406e205ecb41a65f79ad5ec18c20d6fa8795017bba02b84bdf8 |
| `vllm` | vLLM | 3153 | 41% | 192×192 | 03d6565f20f43acf5826639104af0f3bd36c0105b7a0b1f4cd8f213178789ff2 |
| `siliconcloud` | SiliconCloud | 1264 | 27% | 192×192 | 90c77b42d76c1d6381afc9248610076f9fef68e0b4e1a85594b7bec5dd386c74 |
| `volcengine` | Volcengine | 4947 | 44% | 192×192 | 39603a946827b98dced03b24fba8ec40382ea092e3334f1d78c53659691e8724 |
| `baidu` | Baidu | 8062 | 39% | 192×192 | 8c77f28738c86af5f6b64428ec0bfb5b87ec0bdb9fd54a03db4485a93415ea24 |
| `tencent` | Tencent | 3374 | 30% | 192×192 | 5cdaceba35e18e9f7c59d4e76a634807bd58d066d25c238ec65360c8ae5640b0 |
| `huawei` | Huawei | 9133 | 42% | 192×192 | d1093eef4312c9a04f315f5ac528bb8c3e2dd9beaa512cddf9c4ccbf9e6c4de6 |
| `xiaomi` | Xiaomi MiMo | 8648 | 19% | 192×192 | 1d56018beebe1548e13f6caa41d5abc1f4648bfb07c00b4e188d080220945c93 |

## 7. 生成流程（可复现）

整条流水线只用容器内已有工具：`node`（还原 JSX→SVG）、`rsvg-convert`（SVG→PNG）、`PIL`（校验）。
**未安装任何新包**；如换环境，只需 `node` + `librsvg2-bin`（`rsvg-convert`）。

```sh
# 1) 固定版本下载
curl -sL --max-time 60 -o lobehub-icons-6.15.2.tgz \
  https://registry.npmjs.org/@lobehub/icons/-/icons-6.15.2.tgz
sha256sum lobehub-icons-6.15.2.tgz   # 19906e33...18588
tar -xzf lobehub-icons-6.15.2.tgz -C pkg   # → pkg/package/es/<Brand>/components/*.mjs

# 2) 组件 → SVG（extract.mjs，见下）   3) SVG → 192×192 透明 PNG
node extract.mjs pkg/package/es/OpenAI/components/Mono.mjs OpenAI dark > openai.svg
rsvg-convert -w 192 -h 192 --keep-aspect-ratio openai.svg -o openai.png
```

`extract.mjs`：上游组件是 `react/jsx-runtime` 的 `jsx()/jsxs()` 调用树，脚本剥掉 `import`，注入 `memo/jsx/jsxs/useFillIds`
等空实现后直接执行模块，再把对象树序列化为 SVG；`dark` 模式把 `currentColor` 换成 `#1A1A1A`，并去掉 `<title>`。

```js
// extract.mjs —— node extract.mjs <Component.mjs> <Brand> <dark|none> > out.svg
import fs from 'node:fs';
const [, , file, title, mode] = process.argv;
let src = fs.readFileSync(file, 'utf8');
src = src.replace(/^\s*"use client";\s*$/m, '');
src = src.split('\n')
  .filter((l) => !/^\s*import\s/.test(l) && !/^\s*\/\/#/.test(l) && !/sourceMappingURL/.test(l))
  .join('\n');
src = src.replace(/export\s*\{\s*\w+\s+as\s+default\s*\};?/g, '');
const shim = `
const memo = (f) => f;
const jsx = (t, p) => ({ t, p: p || {} });
const jsxs = (t, p) => ({ t, p: p || {} });
const useId = () => 'uid1';
const useFillIds = (t, n) => Array.from({ length: n }, (_, i) => 'f' + i);
const useFillId = () => 'f0';
const TITLE = ${JSON.stringify(title)};
`;
const tail = `
const kebab = { fillRule:'fill-rule', clipRule:'clip-rule', strokeWidth:'stroke-width',
  strokeLinecap:'stroke-linecap', strokeLinejoin:'stroke-linejoin', strokeDasharray:'stroke-dasharray',
  strokeDashoffset:'stroke-dashoffset', strokeMiterlimit:'stroke-miterlimit', strokeOpacity:'stroke-opacity',
  fillOpacity:'fill-opacity', stopColor:'stop-color', stopOpacity:'stop-opacity', fontFamily:'font-family',
  fontSize:'font-size', fontWeight:'font-weight', textAnchor:'text-anchor', letterSpacing:'letter-spacing',
  dominantBaseline:'dominant-baseline', colorInterpolationFilters:'color-interpolation-filters',
  floodColor:'flood-color', floodOpacity:'flood-opacity', xlinkHref:'xlink:href', xmlnsXlink:'xmlns:xlink',
  className:'class' };
const esc = (s) => String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
function render(node) {
  if (node === null || node === undefined || node === false || node === true) return '';
  if (Array.isArray(node)) return node.map(render).join('');
  if (typeof node === 'string' || typeof node === 'number') return esc(node);
  if (typeof node !== 'object' || !node.t || node.t === 'title') return '';
  let attrs = '';
  for (const [k, v] of Object.entries(node.p)) {
    if (k === 'children' || k === 'style' || k === 'key' || k === 'ref') continue;
    if (v === null || v === undefined || typeof v === 'object' || typeof v === 'function') continue;
    attrs += ' ' + (kebab[k] || k) + '="' + esc(v) + '"';
  }
  const kids = node.p.children !== undefined ? render(node.p.children) : '';
  return '<' + node.t + attrs + '>' + kids + '</' + node.t + '>';
}
let svg = render(Icon({ size: '128' }));
${mode === 'dark' ? "svg = svg.replace(/currentColor/g, '#1A1A1A');" : ''}
process.stdout.write('<?xml version="1.0" encoding="UTF-8"?>\\n' + svg + '\\n');
`;
fs.writeFileSync('/tmp/.run.mjs', shim + src + tail);
await import('/tmp/.run.mjs');
```

注意事项：

- 每个 SVG 只写单个文件，`useFillIds()` 生成的渐变 id 在文件内唯一即可，无需全局去重。
- `Color` 变体若某品牌缺失（上游只提供 Mono），自动回退到 Mono 深色版本；`Mono (dark)` 列即该情况。
- 升级上游版本时必须重跑第 6 节的校验，并同步更新本文与 `catalog.json` 的 `file` 集合。

## 8. 建议补入 `THIRD_PARTY_LICENSES.md` 的条目（本次未改动该文件）

在「Bundled web/UI assets」小节追加一行即可：

```markdown
| Model brand icons (`src/android/app/src/main/assets/model-icons/`) | [`@lobehub/icons`](https://github.com/lobehub/lobe-icons) 6.15.2 (`https://registry.npmjs.org/@lobehub/icons/-/icons-6.15.2.tgz`, SHA-256 `19906e33…18588`), colours/monochrome variant rasterised to 192×192 PNG | **MIT** (Copyright (c) 2023 LobeHub), full text at `assets/model-icons/LICENSE-lobehub-icons.txt` | Brand names/logos remain trademarks of their owners; used only to identify the configured model/provider. No SVG decoder is bundled — only PNG at runtime |
```

## 9. 边界与未做事项

- 未新增/修改任何 Kotlin、Gradle、Compose、Coil 配置；未引入 `coil-svg`；未触碰全局配置与其他 `assets` 子目录。
- 模型名称 → 图标的自动匹配（fuzzy 匹配 `id`/`aliases`/provider id、回退策略）由另一任务实现，本目录只提供数据。
- 未提交 git、未执行包管理安装。
- 中间文件（tarball、解包目录、SVG 源、校验脚本）全部位于临时目录 `/tmp/minis-resume-20261116-icons`
  与 `/var/minis/workspace/tmp/minis-resume-20261116/icons`，交付后已清理，不进入仓库。
