FROM node:22-alpine
WORKDIR /app
COPY package.json server.js LICENSE ./
COPY lib ./lib
COPY public ./public
ENV HOST=0.0.0.0 PORT=4066 DATA_DIR=/app/data
EXPOSE 4066
VOLUME /app/data
CMD ["node", "server.js"]
