#
# Builder
#
FROM node:22-alpine3.21 as builder

# Proxy configuration
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG http_proxy
ARG https_proxy
ARG no_proxy

ENV HTTP_PROXY=${HTTP_PROXY}
ENV HTTPS_PROXY=${HTTPS_PROXY}
ENV NO_PROXY=${NO_PROXY}
ENV http_proxy=${http_proxy}
ENV https_proxy=${https_proxy}
ENV no_proxy=${no_proxy}

WORKDIR /opt/azurite

# Install dependencies first
COPY *.json LICENSE NOTICE.txt ./

# Install build dependencies for native modules (like keytar)
RUN apk add --no-cache python3 make g++

# Copy the source code and build the app
COPY src ./src
COPY tests ./tests
RUN  npm ci --unsafe-perm
RUN npm run build && \
  npm install -g --unsafe-perm  --loglevel verbose


# Production image
#
FROM node:22-alpine3.21

# Proxy configuration for production stage
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG http_proxy
ARG https_proxy
ARG no_proxy

ENV HTTP_PROXY=${HTTP_PROXY}
ENV HTTPS_PROXY=${HTTPS_PROXY}
ENV NO_PROXY=${NO_PROXY}
ENV http_proxy=${http_proxy}
ENV https_proxy=${https_proxy}
ENV no_proxy=${no_proxy}
ENV NODE_ENV=production

WORKDIR /opt/azurite

# Default Workspace Volume
VOLUME [ "/data" ]

COPY package*.json LICENSE NOTICE.txt ./

COPY --from=builder /opt/azurite/dist/ dist/

# Install build dependencies for native modules (like keytar)
RUN apk add --no-cache python3 make g++

RUN npm pkg set scripts.prepare="echo no-prepare"

RUN npm ci --unsafe-perm

RUN npm install -g --unsafe-perm --loglevel verbose

# Blob Storage Port
EXPOSE 10000
# Queue Storage Port
EXPOSE 10001
# Table Storage Port
EXPOSE 10002

CMD ["azurite", "-l", "/data", "--blobHost", "0.0.0.0","--queueHost", "0.0.0.0", "--tableHost", "0.0.0.0"]
