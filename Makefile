.PHONY: check server-install server server-identity server-test server-audit android-test android-apk android-release compose-up compose-down compose-identity logs

check: server-test android-test

server-install:
	cd server && npm install --ignore-scripts --no-audit --no-fund

server:
	cd server && node src/server.mjs

server-identity:
	cd server && npm run identity

server-test:
	cd server && npm test && npm run check

server-audit:
	cd server && npm audit --omit=dev --audit-level=high

android-test:
	cd android && ./gradlew --no-daemon testDebugUnitTest --stacktrace

android-apk:
	cd android && ./gradlew --no-daemon testDebugUnitTest assembleDebug --stacktrace

android-release:
	cd android && ./gradlew --no-daemon testDebugUnitTest assembleRelease --stacktrace

compose-up:
	docker compose up --build -d

compose-down:
	docker compose down

compose-identity:
	docker compose exec spatial-server npm run identity

logs:
	docker compose logs -f spatial-server
