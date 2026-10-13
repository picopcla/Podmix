import http from 'node:http'

const port = Number(process.env.PODMIX_FIXTURE_PORT ?? 18787)
const chunk = Buffer.alloc(32 * 1024, 0x2a)
const chunkCount = 64
const requests = { slow: 0, retry: 0 }

function streamFixture(response) {
  response.writeHead(200, {
    'Content-Type': 'audio/mpeg',
    'Content-Length': chunk.length * chunkCount,
    'Cache-Control': 'no-store',
  })
  let sent = 0
  const timer = setInterval(() => {
    if (sent >= chunkCount) {
      clearInterval(timer)
      response.end()
      return
    }
    response.write(chunk)
    sent += 1
  }, 60)
  response.on('close', () => clearInterval(timer))
}

const server = http.createServer((request, response) => {
  if (request.url === '/stats') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify(requests))
    return
  }
  if (request.url === '/slow.mp3') {
    requests.slow += 1
    streamFixture(response)
    return
  }
  if (request.url === '/retry.mp3') {
    requests.retry += 1
    if (requests.retry === 1) {
      response.writeHead(404, { 'Content-Type': 'text/plain' })
      response.end('échec factice')
      return
    }
    streamFixture(response)
    return
  }
  response.writeHead(404)
  response.end()
})

server.listen(port, '0.0.0.0', () => {
  console.log(`Fixture audio factice prête sur le port ${port}`)
})
