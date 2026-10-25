# 第三方数据与许可说明

本项目的**代码**由作者编写；但 `app/src/main/assets/pinyin.txt`（拼音词库）
是**从第三方词库生成**的，使用时必须遵守其上游许可。

## 词库来源

`pinyin.txt` 由 `tools/gen-dict.ps1` 生成，数据来自：

| 上游 | 说明 | 许可 |
|---|---|---|
| [rime-ice](https://github.com/iDvel/rime-ice) | 词库本体（`cn_dicts/8105.dict.yaml`、`cn_dicts/base.dict.yaml`） | **GPL-3.0** |
| 华宇野风系统词库 | rime-ice 的组成部分之一 | 见 rime-ice 仓库说明 |
| [THUOCL](https://github.com/thunlp/THUOCL)（清华开源词库） | rime-ice 的组成部分之一 | 见上游仓库 |
| 《现代汉语常用词表》 | rime-ice 的组成部分之一 | 见 rime-ice 仓库说明 |
| 腾讯词向量 | rime-ice 用它补充常用词 | 见 rime-ice 仓库说明 |

`pinyin.txt` 是上述数据的**衍生作品**，因此随本项目分发时同样受 **GPL-3.0** 约束。

## 如果你的项目要商用 / 上架应用商店

**不要直接使用这个 `pinyin.txt`**，或者：

1. 把整个项目以 GPL-3.0 发布，并保留本说明；或
2. 换用许可更宽松的词库（例如基于 [Unihan](https://www.unicode.org/charts/unihan.html)
   自建单字表），或购买商业词库授权。

生成脚本 `tools/gen-dict.ps1` 是通用的：只要把输入换成你有权使用的词库即可。

## 应用图标

`app/src/main/res/mipmap-*` 下的图标由 `tools/make-icon.ps1` 用代码绘制生成，
不包含任何第三方素材。

## 签名密钥

仓库**不包含**签名密钥（`keystore/`、`keystore.properties` 已加入 `.gitignore`）。
要发布自己的版本，请自行生成密钥库：

```powershell
keytool -genkeypair -v -keystore keystore/release.jks -alias youralias `
  -keyalg RSA -keysize 2048 -validity 10950
```
