# ReadyGym contributor guidance

Read README.md, docs/development.md and THIRD_PARTY_NOTICES.md first.
Keep Android app/core and server tests green. Do not bypass beginner HIGH_RISK filtering.
Keep Room migrations non-destructive; retain v1/v2 schemas. Rest starts at 30 seconds.
Do not remove the legacy /api/asr endpoint. Keep external services mocked in unit tests.
Never add credentials, personal databases or unlicensed exercise media to Git.
Original code and documentation use Apache-2.0; brand assets and third-party material do not.
Use readable Chinese UI copy. Changes to AI/provider/network configuration must remain optional.
No automatic production deployment or app-store upload from this public source repository.
