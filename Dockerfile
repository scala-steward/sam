FROM us.gcr.io/broad-dsp-gcr-public/base/jre:11-debian

# To run, build jar using ./docker/build.sh

EXPOSE 8080
EXPOSE 5050

ENV GIT_MODEL_HASH $GIT_MODEL_HASH

RUN mkdir /sam
COPY ./sam*.jar /sam
# Add Sam as a service (it will start when the container starts)
CMD java $JAVA_OPTS -jar $(find /sam -name 'sam*.jar')
