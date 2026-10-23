#!/usr/bin/env bash
# Suite DIGSA · trabajo «backend»: reglas (JUnit), integración y API con PostgreSQL 16 embebido (zonky), arquitectura
# (Spring Modulith, ArchUnit). Sin Docker. Corre igual en Linux y en Git Bash de Windows.
# Informes JUnit: backend/*/target/surefire-reports y failsafe-reports.
. "$(dirname "$0")/comun.sh"

no_root
info "JDK"
preparar_java

info "mvnw verify (TZ=$TZ)"
mvnw -B -ntp -Duser.timezone="$TZ" verify "$@"
info "Backend en verde."
