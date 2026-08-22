from pathlib import Path

import pytest

SPEC_DIR = Path(__file__).resolve().parents[2] / "spec"


@pytest.fixture(scope="session")
def spec_dir() -> Path:
    return SPEC_DIR
