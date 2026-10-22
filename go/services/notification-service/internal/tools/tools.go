//go:build tools

// Package tools pins every third-party module the service uses so `go mod
// tidy` keeps them (and their go.sum entries) even while a package that will
// import them is not written yet. It is never compiled into the binary.
package tools

import (
	_ "github.com/IBM/sarama"
	_ "github.com/aws/aws-sdk-go-v2/aws"
	_ "github.com/aws/aws-sdk-go-v2/config"
	_ "github.com/aws/aws-sdk-go-v2/credentials"
	_ "github.com/aws/aws-sdk-go-v2/service/sesv2"
	_ "github.com/aws/aws-sdk-go-v2/service/sesv2/types"
	_ "github.com/aws/aws-sdk-go-v2/service/sqs"
	_ "github.com/aws/aws-sdk-go-v2/service/sqs/types"
	_ "github.com/gin-gonic/gin"
	_ "github.com/golang-migrate/migrate/v4"
	_ "github.com/golang-migrate/migrate/v4/database/pgx/v5"
	_ "github.com/golang-migrate/migrate/v4/source/iofs"
	_ "github.com/google/uuid"
	_ "github.com/jackc/pgx/v5"
	_ "github.com/jackc/pgx/v5/pgconn"
	_ "github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/prometheus/client_golang/prometheus"
	_ "github.com/prometheus/client_golang/prometheus/promhttp"
	_ "github.com/redis/go-redis/v9"
	_ "github.com/santhosh-tekuri/jsonschema/v6"
	_ "github.com/spf13/viper"
	_ "github.com/stretchr/testify/assert"
	_ "github.com/stretchr/testify/require"
	_ "github.com/testcontainers/testcontainers-go"
	_ "github.com/testcontainers/testcontainers-go/modules/kafka"
	_ "github.com/testcontainers/testcontainers-go/modules/postgres"
	_ "github.com/testcontainers/testcontainers-go/modules/redis"
	_ "github.com/testcontainers/testcontainers-go/wait"
	_ "github.com/xdg-go/scram"
	_ "go.uber.org/zap"
	_ "gopkg.in/yaml.v3"
)
