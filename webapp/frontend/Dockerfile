FROM node:20-alpine AS builder
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .

# Baked into the built JS bundle — see webapp/README.md's auth-model caveat
# about this being a coarse app-level gate, not a strong secret.
ARG VITE_API_BASE=""
ARG VITE_API_CLIENT_ID
ARG VITE_API_CLIENT_SECRET
ARG VITE_READER_AGENT_URL=ws://127.0.0.1:17420/ws
ENV VITE_API_BASE=$VITE_API_BASE \
    VITE_API_CLIENT_ID=$VITE_API_CLIENT_ID \
    VITE_API_CLIENT_SECRET=$VITE_API_CLIENT_SECRET \
    VITE_READER_AGENT_URL=$VITE_READER_AGENT_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=builder /src/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
