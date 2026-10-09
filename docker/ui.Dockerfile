FROM nginxinc/nginx-unprivileged:stable-alpine
ARG APP
COPY ui/${APP}/dist/ /usr/share/nginx/html/
