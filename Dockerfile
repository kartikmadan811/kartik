FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY LostFoundServer.java .
COPY public ./public
RUN javac LostFoundServer.java
CMD ["java", "LostFoundServer"]
