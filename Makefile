URL ?= http://localhost:8080

.PHONY: burst
burst:
	./burst.sh $(URL)
