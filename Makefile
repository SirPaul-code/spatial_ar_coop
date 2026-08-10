.PHONY: check server server-test android-test android-apk compose-up compose-down logs

check: server-test android-test

server:
	cd server && node src/server.mjs

server-test:
	cd server && npm test && npm run check

android-test:
	cd android && ./gradlew testDebugUnitTest --stacktrace

android-apk:
	cd android && ./gradlew assembleDebug --stacktrace

compose-up:
	docker compose up --build -d

compose-down:
	docker compose down

logs:
	docker compose logs -f spatial-server
