URL ?= http://localhost:8080

.PHONY: burst
burst:
	./burst.sh $(URL)

.PHONY: burst-docker
burst-docker:
	docker compose run --rm burst
