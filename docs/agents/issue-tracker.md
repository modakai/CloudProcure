# Issue tracker: GitHub

需求、规格和任务统一记录在 [modakai/CloudProcure 的 GitHub Issues](https://github.com/modakai/CloudProcure/issues)。
使用 `gh` CLI，显式指定 `--repo modakai/CloudProcure`。

## Conventions

- 创建：`gh issue create --repo modakai/CloudProcure --title "标题" --body-file <正文文件>`。
- 读取：`gh issue view <编号> --repo modakai/CloudProcure --json number,title,body,labels,comments`。
- 列表：`gh issue list --repo modakai/CloudProcure --state open`。
- 添加或移除标签：`gh issue edit <编号> --repo modakai/CloudProcure --add-label <标签>` 或 `--remove-label <标签>`。
- 评论：`gh issue comment <编号> --repo modakai/CloudProcure --body-file <正文文件>`。
- 关闭：`gh issue close <编号> --repo modakai/CloudProcure`。

多行正文通过 UTF-8 文件传递。
技能要求“发布到需求跟踪器”时，创建 GitHub Issue；要求“获取相关任务”时，读取对应 Issue。

## Pull requests as a triage surface

**PRs as a request surface: no.**
