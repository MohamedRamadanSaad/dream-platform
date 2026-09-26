# Build the SPA, serve it with nginx (SPA fallback + long cache for hashed assets)
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_API_URL=/api
ARG VITE_USE_MOCKS=true
ARG VITE_GOOGLE_CLIENT_ID=
ARG VITE_YOUTUBE_URL=https://youtube.com/@almoaberafatema
ENV VITE_API_URL=$VITE_API_URL VITE_USE_MOCKS=$VITE_USE_MOCKS VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID VITE_YOUTUBE_URL=$VITE_YOUTUBE_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
