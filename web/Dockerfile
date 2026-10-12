# ---- build ----
# static files are the same on every chip: build natively, serve from a per-platform nginx
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---- serve ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
LABEL org.opencontainers.image.source="https://github.com/babyjose-droid/AI-ML-LOS"
EXPOSE 80
