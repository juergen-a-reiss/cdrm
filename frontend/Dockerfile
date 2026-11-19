# syntax=docker/dockerfile:1

# Built natively on the CI runner ($BUILDPLATFORM): dist/ is static files, only the nginx
# runtime stage below needs the target architecture.
FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Generates /config.js from OIDC_AUTHORITY/OIDC_CLIENT_ID at container start.
COPY --chmod=755 docker-entrypoint.d/40-cdrm-config.sh /docker-entrypoint.d/
EXPOSE 80
