import assert from 'node:assert/strict'
import test from 'node:test'
import { readNdjson } from '../src/ndjson.ts'

test('분할된 UTF-8 청크와 마지막 줄을 순서대로 읽는다', async () => {
  const bytes = new TextEncoder().encode('{"type":"delta","text":"안녕"}\n{"type":"done"}')
  const chunks = [bytes.slice(0, 27), bytes.slice(27, 29), bytes.slice(29, 39), bytes.slice(39)]
  const stream = new ReadableStream({ start(controller) { chunks.forEach(chunk => controller.enqueue(chunk)); controller.close() } })
  const events = []
  for await (const event of readNdjson(stream)) events.push(event)
  assert.deepEqual(events, [{ type: 'delta', text: '안녕' }, { type: 'done' }])
})

test('손상된 이벤트는 조용히 무시하지 않는다', async () => {
  const stream = new ReadableStream({ start(controller) { controller.enqueue(new TextEncoder().encode('{broken}\n')); controller.close() } })
  await assert.rejects(async () => {
    for await (const event of readNdjson(stream)) void event
  }, SyntaxError)
})
