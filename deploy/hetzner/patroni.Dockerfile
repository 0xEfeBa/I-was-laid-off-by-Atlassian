FROM postgres:17.11
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-venv \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m venv /opt/patroni \
    && /opt/patroni/bin/pip install --no-cache-dir "patroni[etcd3]==4.0.11" "psycopg[binary]==3.3.6"
COPY patroni.yml /etc/patroni.yml
USER postgres
ENTRYPOINT ["/opt/patroni/bin/patroni", "/etc/patroni.yml"]
