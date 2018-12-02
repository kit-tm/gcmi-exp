echo "configuring transparent middle layer"
curl -f -H "Content-Type: application/json" -X POST -d "$(< configs/config.json)" \
localhost:8080/layers

curl -f -H "Content-Type: application/json" -X POST -d '
{
	"source": { "layer" : 1 },
	"targets": [ { "address" : "127.0.0.1:6653" } ]
}
' localhost:8080/edges


ryu-manager $3 --num-links $1 --num-repetitions $2 apps/test_router_13.py
