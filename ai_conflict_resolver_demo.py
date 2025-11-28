# scripts/ops/ai_conflict_resolver_demo.py
#!/usr/bin/env python3
"""
Demo: 使用reconcile-ai自动解决Git冲突
用法:
    python ai_conflict_resolver_demo.py <PR_NUMBER> <TARGET_BRANCH> [REPO_URL]

示例:
    python ai_conflict_resolver_demo.py 12345 release-8.5
    python ai_conflict_resolver_demo.py 12345 release-8.5 https://github.com/pingcap/tidb
"""
import sys
import os
import subprocess
import urllib.request
import tempfile
from pathlib import Path

def check_dependencies():
    """检查并安装依赖"""
    print("📦 检查依赖...")

    # 检查reconcile-ai
    try:
        result = subprocess.run(
            ["reconcile", "--version"],
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            print("✅ reconcile-ai 已安装")
            return True
    except FileNotFoundError:
        pass

    # 安装reconcile-ai
    print("📥 安装reconcile-ai...")
    try:
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "-q", "reconcile-ai"],
            check=True
        )
        print("✅ reconcile-ai 安装完成")
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ 安装失败: {e}")
        return False

def get_pr_patch(pr_number, repo_url):
    """下载PR的patch文件"""
    print(f"📥 下载PR #{pr_number}的patch...")

    # 提取repo路径
    if "github.com" in repo_url:
        repo_path = repo_url.replace("https://github.com/", "").replace(".git", "")
    else:
        repo_path = repo_url

    patch_url = f"https://github.com/{repo_path}/pull/{pr_number}.patch"

    try:
        patch_file = tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.patch')
        patch_path = patch_file.name
        patch_file.close()

        urllib.request.urlretrieve(patch_url, patch_path)
        print(f"✅ Patch已下载: {patch_path}")
        return patch_path
    except Exception as e:
        print(f"❌ 下载失败: {e}")
        return None

def git_checkout_branch(branch):
    """切换到目标分支"""
    print(f"📋 切换到分支: {branch}")

    try:
        # 先fetch
        subprocess.run(["git", "fetch", "origin", branch], check=True, capture_output=True)
        # checkout
        subprocess.run(["git", "checkout", f"origin/{branch}"], check=True)
        print(f"✅ 已切换到 {branch}")
        return True
    except subprocess.CalledProcessError as e:
        print(f"❌ 切换分支失败: {e}")
        return False

def create_cherry_pick_branch(pr_number):
    """创建cherry-pick分支"""
    branch_name = f"auto-cp-pr-{pr_number}"
    print(f"🌿 创建分支: {branch_name}")

    try:
        subprocess.run(["git", "checkout", "-b", branch_name], check=True)
        print(f"✅ 分支已创建: {branch_name}")
        return branch_name
    except subprocess.CalledProcessError as e:
        print(f"❌ 创建分支失败: {e}")
        return None

def apply_patch(patch_file):
    """应用patch，返回是否有冲突"""
    print("🔀 应用patch...")

    try:
        result = subprocess.run(
            ["git", "am", "-3", patch_file],
            capture_output=True,
            text=True
        )

        if result.returncode == 0:
            print("✅ Patch应用成功，没有冲突！")
            commit_sha = subprocess.check_output(
                ["git", "rev-parse", "HEAD"],
                text=True
            ).strip()
            print(f"📌 Commit: {commit_sha[:8]}")
            return True, None
        else:
            # 检查是否有冲突
            status = subprocess.run(
                ["git", "status", "--porcelain"],
                capture_output=True,
                text=True
            )

            if "UU" in status.stdout or "<<<<<<<" in status.stdout:
                print("⚠️  检测到冲突")
                return False, "conflict"
            else:
                print(f"❌ Patch应用失败: {result.stderr}")
                return False, "error"

    except Exception as e:
        print(f"❌ 应用patch时出错: {e}")
        return False, "error"

def resolve_conflicts_with_ai(dry_run=False):
    """使用reconcile-ai解决冲突"""
    print("\n🤖 使用reconcile-ai解决冲突...")
    print("="*50)

    # 检查API key
    if not os.environ.get('OPENAI_API_KEY'):
        print("❌ 错误: 未设置 OPENAI_API_KEY 环境变量")
        return False

    # 构建命令
    cmd = ["reconcile", "run"]

    if dry_run:
        cmd.append("--dry-run")
        print("🔍 Dry-run模式: 只检测冲突，不解决")
    else:
        cmd.append("--verbose")
        print("🔧 开始解决冲突...")

    # 执行
    try:
        result = subprocess.run(
            cmd,
            env=os.environ,
            text=True
        )

        print("="*50)

        if result.returncode == 0:
            if dry_run:
                print("✅ 冲突检测完成（dry-run模式）")
            else:
                print("✅ reconcile-ai 执行成功")
            return True
        else:
            print(f"⚠️  reconcile-ai 返回非零退出码: {result.returncode}")
            return False

    except FileNotFoundError:
        print("❌ reconcile-ai 未找到，请先安装: pip install reconcile-ai")
        return False
    except Exception as e:
        print(f"❌ 执行失败: {e}")
        return False

def continue_cherry_pick():
    """继续cherry-pick"""
    print("\n✅ 冲突已解决，继续cherry-pick...")

    try:
        # 添加所有文件
        subprocess.run(["git", "add", "."], check=True)

        # 继续cherry-pick
        subprocess.run(["git", "am", "--continue"], check=True)

        commit_sha = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            text=True
        ).strip()

        print("✅ Cherry-pick完成！")
        print(f"📌 Commit: {commit_sha[:8]}")
        return True

    except subprocess.CalledProcessError as e:
        print(f"❌ 继续cherry-pick失败: {e}")
        return False

def check_remaining_conflicts():
    """检查是否还有未解决的冲突"""
    status = subprocess.run(
        ["git", "status", "--porcelain"],
        capture_output=True,
        text=True
    )

    # 检查冲突标记
    if "UU" in status.stdout:
        return True

    # 检查文件中的冲突标记
    try:
        result = subprocess.run(
            ["git", "diff", "--check"],
            capture_output=True,
            text=True
        )
        if "<<<<<<<" in result.stdout:
            return True
    except:
        pass

    return False

def main():
    """主函数"""
    if len(sys.argv) < 3:
        print("用法: python ai_conflict_resolver_demo.py <PR_NUMBER> <TARGET_BRANCH> [REPO_URL]")
        print("\n示例:")
        print("  python ai_conflict_resolver_demo.py 12345 release-8.5")
        print("  python ai_conflict_resolver_demo.py 12345 release-8.5 https://github.com/pingcap/tidb")
        print("\n环境变量:")
        print("  OPENAI_API_KEY: OpenAI API密钥（必需）")
        sys.exit(1)

    pr_number = sys.argv[1]
    target_branch = sys.argv[2]
    repo_url = sys.argv[3] if len(sys.argv) > 3 else "https://github.com/pingcap/tidb"

    print("="*50)
    print("🚀 AI冲突解决Demo")
    print("="*50)
    print(f"PR: #{pr_number}")
    print(f"Target Branch: {target_branch}")
    print(f"Repository: {repo_url}")
    print("="*50)
    print()

    # 检查当前目录是否是git仓库
    if not os.path.exists(".git"):
        print("❌ 错误: 当前目录不是Git仓库")
        print("   请先clone仓库: git clone <repo-url>")
        sys.exit(1)

    # 检查依赖
    if not check_dependencies():
        sys.exit(1)

    # 检查API key
    if not os.environ.get('OPENAI_API_KEY'):
        print("❌ 错误: 请设置环境变量 OPENAI_API_KEY")
        print("   例如: export OPENAI_API_KEY='sk-...'")
        sys.exit(1)

    # Step 1: 下载patch
    patch_file = get_pr_patch(pr_number, repo_url)
    if not patch_file:
        sys.exit(1)

    # Step 2: Checkout目标分支
    if not git_checkout_branch(target_branch):
        sys.exit(1)

    # Step 3: 创建cherry-pick分支
    cp_branch = create_cherry_pick_branch(pr_number)
    if not cp_branch:
        sys.exit(1)

    # Step 4: 应用patch
    success, error_type = apply_patch(patch_file)

    if success:
        # 没有冲突，直接成功
        print("\n" + "="*50)
        print("✅ Demo完成！没有冲突，cherry-pick成功")
        print("="*50)
        sys.exit(0)

    if error_type == "error":
        # 非冲突错误
        print("\n" + "="*50)
        print("❌ Demo失败：patch应用出错")
        print("="*50)
        sys.exit(1)

    # 有冲突，使用AI解决
    if not resolve_conflicts_with_ai():
        print("\n" + "="*50)
        print("❌ AI冲突解决失败")
        print("="*50)
        sys.exit(1)

    # 检查是否还有冲突
    if check_remaining_conflicts():
        print("\n⚠️  仍有未解决的冲突，请手动检查")
        print("   运行 'git status' 查看详情")
        sys.exit(1)

    # 继续cherry-pick
    if not continue_cherry_pick():
        sys.exit(1)

    # 清理临时文件
    try:
        os.unlink(patch_file)
    except:
        pass

    print("\n" + "="*50)
    print("✅ Demo完成！所有冲突已解决，cherry-pick成功")
    print("="*50)
    print(f"\n📌 当前分支: {cp_branch}")
    print("   可以运行以下命令查看更改:")
    print(f"   git log --oneline -1")
    print(f"   git diff HEAD~1")
    print("="*50)

    sys.exit(0)

if __name__ == '__main__':
    main()
