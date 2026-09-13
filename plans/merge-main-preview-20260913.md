# 合并预演报告：`main` ← `codex/count-positive`

预演时间：2026-09-13 21:30
预演方式：`git merge-tree --write-tree --name-only`（**离线，不触碰工作区**）

---

## 一、合并双方

| 项目 | 值 |
|---|---|
| 目标分支 | `main` @ `bf8e172` |
| 来源分支 | `codex/count-positive` @ `94dae59` |
| 共同祖先 | `8d98b21`（08-13，v0.3.0 基线） |
| 来源领先提交数 | **17 个**（rank-accuracy 13 + count-positive 3 + 文档 1） |
| 预演结果树 | `be9605f` |

---

## 二、⚠️ 修正：冲突数量远低于此前预估

**此前预估「8 处冲突」是错误的口径** —— 那是拿 `positive-accumulate` 当合并目标算的。
`positive-accumulate` 本身改动多，所以冲突多。

**真实情况**：`main` 相对共同祖先 `8d98b21` **只改了 2 个文件**：

```
M  .gitignore
A  README.md
```

因此合并到 `main` 的冲突只有 **1 处**。

---

## 三、冲突清单（共 1 处，且为「假冲突」）

### `.gitignore`

**冲突成因**：两边在**同一位置**添加了**相同**的 2 行（`.tmp*` / `*.tmp`），
git 判定内容冲突，但两边意愿完全一致。

| 版本 | 内容 |
|---|---|
| 共同祖先 `8d98b21` | 到 `*.jpg` 结束 |
| `main` | `*.jpg` + `.tmp*` + `*.tmp` |
| `count-positive` | `*.jpg` + `.tmp*` + `*.tmp` + `docs/` + `.workbuddy-ai/` |

**解决方案**：**取 `count-positive` 版**。它是 `main` 版的**严格超集**
（包含 main 的全部内容 + 2 个新增忽略块）。

```gitignore
# 最终结果
.gradle/
**/build/
.kotlin/
.idea/
*.iml
local.properties
captures/
*.jks
*.keystore
releases/**/*.apk
releases/**/*-source.zip
.workbuddy/
9局/
g2-analysis/
*.mp4
*.jpg
.tmp*
*.tmp

# 视觉标定临时工作区：165 张诊断/截图 PNG（133MB）+ 一次性调试脚本
# 保留在磁盘供本地标定使用，不进版本控制
docs/

# 工具目录：WorkBuddy 记忆与工作区状态，不入库
.workbuddy-ai/
```

---

## 四、非冲突项确认

### `README.md`（main 侧新增，自动保留）

- `main` 相对共同祖先**新增** `README.md`（+66 行）
- `count-positive` 侧**完全没有 README**（它从 `8d98b21` 直接分出，未经 main 的 README 提交）
- 预演结果树中的 README blob = `55b23c2`，**与 `main:README.md` 完全一致** ✅
- → 自动合并，**无需人工干预**

### 源码文件（29 个）

`count-positive` 相对共同祖先改动 31 个文件（+3219/-194），其中源码文件全部是**单边改动**
（main 侧未碰）→ **全部自动合并，零冲突**。

---

## 五、预演树验证

| 对比 | 结果 | 判定 |
|---|---|---|
| 预演树 `be9605f` vs `count-positive` | `.gitignore`(3 行冲突标记) + `README.md`(66 行) | ✅ 前者是待解假冲突，后者是 main 带进来的新文件 |
| 预演树 `be9605f` vs `main` | 31 文件 +3220/-194 | ✅ count-positive 的全部改动都进去了 |

---

## 六、风险评估

| 风险项 | 等级 | 说明 |
|---|---|---|
| 核心会计语义冲突 | **无** | main 侧**零源码改动**，不存在 GameReducer/GameState 双向修改 |
| 代码质量 | **无** | 合并后代码 = count-positive（已 62/62 测试通过） |
| 数据丢失 | **无** | main 侧仅 2 个文档/配置文件，README 自动保留、.gitignore 有明确解法 |
| git 写操作故障 | **高** | ⚠️ 本环境 git 写操作会删 `refs/heads/codex/` 目录（已四次复现） |

**注**：风险不在合并本身，而在**本环境 git 写操作的不可靠性**。

---

## 七、执行方案（建议）

### 关键约束
**绝不在主 worktree 执行合并** —— 主 worktree 被 `positive-accumulate` 占用，
在里面操作会污染分支语义。

### 推荐做法：用独立 worktree 执行合并

```bash
# 1. 备份 main 当前值（记下来，ref 丢了可重建）
#    main = bf8e1729cb4a7028f17f00d1ae5f26f459c6b2d3

# 2. 为 main 建一个独立 worktree（避开主目录）
git worktree add "../_merge-main" main
#    ⚠️ worktree add 也是写操作，若失败则改用：
#    直接在主目录的 .git 里操作，或用下面的「纯 shell 方案」

# 3. 进 worktree 执行合并
cd "../_merge-main"
git merge codex/count-positive --no-ff -m "merge: count-positive into main"

# 4. 解 .gitignore 冲突（取 count-positive 版）
cp <count-positive>/.gitignore ./.gitignore
git add .gitignore
git commit --no-edit

# 5. ⚠️ 立即验证 ref（本环境 commit 后会删 ref 目录）
git log --oneline -3
cat .git/refs/heads/main

# 6. ⚠️ 若 ref 丢失，用 shell 直写恢复
printf "%s\n" "<完整40位hash>" > <repo>/.git/refs/heads/main

# 7. 编译 + 测试验收
./gradlew.bat testDebugUnitTest --console=plain
./gradlew.bat assembleDebug --console=plain

# 8. ⚠️ 立刻 push 固化
env -u HTTP_PROXY -u HTTPS_PROXY -u http_proxy -u https_proxy \
  git push origin main
```

### 验收标准
- `testDebugUnitTest` → **62/62 通过**（与 count-positive 一致）
- `assembleDebug` → BUILD SUCCESSFUL
- 合并后 `git diff count-positive main -- '*.kt'` → 为空（源码完全一致）
- 只有 `.gitignore` / `README.md` 等文档差异

---

## 八、结论

**合并风险等级：低。**

唯一实质工作是解决 `.gitignore` 的 3 行假冲突（取 count-positive 版即可）。
主要风险来自**本环境 git 写操作的不稳定性**，需严格遵守「commit 后立即验证 ref + 立即 push」流程。
