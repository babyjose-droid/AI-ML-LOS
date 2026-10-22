# Rhythm LOS mobile app, web build: the same React Native screens served in a phone browser (used for the recorded tests
# and as an installable fallback). The phone apps are run with Expo Go or built with EAS; see mobile/README.md.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN EXPO_OFFLINE=1 CI=1 npx expo export --platform web --output-dir dist

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
LABEL org.opencontainers.image.source="https://github.com/babyjose-droid/AI-ML-LOS"
EXPOSE 80
