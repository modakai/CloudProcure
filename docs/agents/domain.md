# Domain Docs

采用 single-context 布局：根目录 `GLOSSARY.md` 与 `docs/adr/`。

## 探索业务代码前

- 阅读 `GLOSSARY.md`，输出中的业务概念沿用其中术语。
- 阅读 `docs/adr/` 中与当前任务相关的架构决策。
- 若将来出现 `GLOSSARY-MAP.md`，按映射阅读相关领域的术语表和 ADR，并检查 `src/<context>/docs/adr/`。

文档不存在时继续工作；术语或决策明确后，由 `domain-modeling` 按需补充。
所需概念未在术语表中定义时，先核对是否符合项目语言；确有缺口则记录供 `domain-modeling` 处理。
方案与既有 ADR 冲突时，明确指出对应 ADR 和重新讨论的理由。
