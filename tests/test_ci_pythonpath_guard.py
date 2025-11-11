from pathlib import Path


def test_ci_workflow_exports_parent_pythonpath():
    contents = Path(".github/workflows/ci_cd.yml").read_text(encoding="utf-8")
    assert 'PYTHONPATH=$(pwd)/../..' in contents
