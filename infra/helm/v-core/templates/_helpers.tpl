{{- define "v-core.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "v-core.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "v-core.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "v-core.labels" -}}
app.kubernetes.io/name: {{ include "v-core.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

