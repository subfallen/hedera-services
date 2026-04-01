FROM eclipse-temurin:25-jre

WORKDIR /app

# Create required data subdirectories
RUN mkdir -p data/config data/recordStreams data/blockStreams data/keys

# Copy dependencies
COPY hedera-node/data/lib/ /app/lib/
COPY SimpleERC20.bin /app/SimpleERC20.bin
COPY MockSupraOraclePull.bin /app/MockSupraOraclePull.bin
COPY LambdaplexFeeCollector.bin /app/LambdaplexFeeCollector.bin
COPY SaucerSwapWHBAR.bin /app/SaucerSwapWHBAR.bin
COPY SaucerSwapV2Factory.bin /app/SaucerSwapV2Factory.bin
COPY SaucerSwapV2SwapRouter.bin /app/SaucerSwapV2SwapRouter.bin
COPY SaucerSwapV2Quoter.bin /app/SaucerSwapV2Quoter.bin
COPY SaucerSwapV2Bootstrapper.bin /app/SaucerSwapV2Bootstrapper.bin

# Copy config
COPY hedera-node/log4j2.xml /app/log4j2.xml
COPY hedera-node/config.txt /app/config.txt
COPY hedera-node/data/keys/generate.sh /app/data/keys/generate.sh
COPY hedera-node/data/config/api-permission.properties /app/data/config/api-permission.properties
COPY hedera-node/configuration/dev/genesis-network.json /app/data/config/genesis-network.json

# Copy application JAR
COPY hedera-node/hedera-app/build/libs/app-0.73.0-SNAPSHOT.jar /app/app.jar

# Run with lib/* on classpath
ENTRYPOINT ["java", "-cp", "/app/app.jar:/app/lib/*", "com.hedera.node.app.ServicesMain", "-local", "0"]
