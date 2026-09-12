import pytest

from backend.core import auth


def test_hash_verify_roundtrip():
    h = auth.hash_password("Med@2026")
    assert h.startswith("pbkdf2$sha256$")
    assert auth.verify_password("Med@2026", h)
    assert not auth.verify_password("wrong", h)


def test_token_roundtrip():
    tok = auth.create_access_token("doctor01", "doctor")
    payload = auth.decode_token(tok)
    assert payload and payload["sub"] == "doctor01" and payload["role"] == "doctor"


def test_bad_token_rejected():
    assert auth.decode_token("not.a.jwt") is None


def test_illegal_role_rejected():
    with pytest.raises(ValueError):
        auth.create_user("x", "pw", "hacker")


def test_seed_and_authenticate(tmp_path, monkeypatch):
    monkeypatch.setattr(auth, "USERS_FILE", str(tmp_path / "users.json"))
    auth.seed_default_users()
    assert auth.authenticate("doctor01", "Med@2026") == {"username": "doctor01", "role": "doctor"}
    assert auth.authenticate("doctor01", "bad") is None
    assert auth.authenticate("ghost", "Med@2026") is None
