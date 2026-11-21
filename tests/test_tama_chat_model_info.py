from pathlib import Path

CHAT = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java/com/openminis/app/ui/chat"


def test_model_name_opens_usage_without_replacing_group_picker():
    screen = (CHAT / "ChatScreen.kt").read_text()
    assert 'onClickLabel = stringResource(R.string.nm_chat_model_info_title)' in screen
    assert '} else Modifier,' in screen
    assert ') { showModelPicker = true }' in screen
    assert 'displayedSessionId = nmDisplayedSessionId' in screen
    sheet = (CHAT / "TokenUsageSheet.kt").read_text()
    assert 'ModelBadges(entry, activeModel.metadata)' in sheet
    assert 'onClick = onChangeModel, enabled = !streaming' in sheet
    assert 'nm_usage_last_reported_context' in sheet
    assert 's?.hasReportedUsage == true' in sheet
