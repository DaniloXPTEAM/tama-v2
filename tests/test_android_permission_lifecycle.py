"""Session-scoped offload approvals must end with the conversation that earned them.

These source checks cover the Android lifecycle wiring; behavior of the permission
queue and invalidation is exercised by OffloadPermissionQueueTest.
"""

from pathlib import Path

import pytest

ANDROID = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


def method_source(path: str, start: str, end: str) -> str:
    source = (ANDROID / path).read_text(encoding="utf-8")
    return source.split(start, 1)[1].split(end, 1)[0]


@pytest.mark.parametrize(
    ("path", "start", "end", "session", "mutation"),
    [
        (
            "com/openminis/app/ui/chat/ChatViewModel.kt",
            "    fun clearChat() {",
            "    // ─── Share Injection",
            "sid",
            "_messages.value = emptyList()",
        ),
        (
            "com/openminis/app/data/repository/ChatRepository.kt",
            "    suspend fun deleteSession(id: String) {",
            "    // ─── Session groups",
            "id",
            "dao.deleteMessages(id)",
        ),
        (
            "io/github/nanomuse/sync/LocalChats.kt",
            "    override suspend fun deleteSession(id: String) {",
            "    override suspend fun clearMessages",
            "id",
            "dao.deleteMessages(id)",
        ),
        (
            "io/github/nanomuse/sync/LocalChats.kt",
            "    override suspend fun clearMessages(sessionId: String) {",
            "    override suspend fun insertMessage",
            "sessionId",
            "dao.deleteMessages(sessionId)",
        ),
        (
            "io/github/nanomuse/account/AccountData.kt",
            "    private suspend fun deleteChats(ctx: Context, account: String) {",
            "    private fun move(",
            "id",
            "repo.dao.deleteMessages(id)",
        ),
    ],
)
def test_conversation_clear_or_delete_withdraws_offload_approvals(
    path: str, start: str, end: str, session: str, mutation: str
) -> None:
    body = method_source(path, start, end)
    cleanup = f"OffloadPermissionManager.clearSessionGrants({session})"
    assert cleanup in body
    assert body.index(cleanup) < body.index(mutation)


def test_releasing_view_model_clears_both_original_and_persisted_session() -> None:
    body = method_source(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    override fun onCleared() {",
        "    /**\n     * T-android-new-chat-empty-residue",
    )
    assert "OffloadPermissionManager.clearSessionGrants(activeSessionId)" in body
    assert "OffloadPermissionManager.clearSessionGrants(sessionId)" in body
