import test from 'node:test'
import assert from 'node:assert/strict'
import { documentsForScope, categoryNames, categoriesForUpload } from '../src/library.ts'

const shared = { id: 'shared', scope: 'SHARED', category: '공식 문서' }
const personal = { id: 'personal', scope: 'PRIVATE', category: '내 자료' }

test('문서 범위를 바꾸는 즉시 이전 범위 문서는 목록에서 제외한다', () => {
  assert.deepEqual(documentsForScope([shared, personal], 'PRIVATE'), [personal])
})

test('관리자는 내 문서를 보는 중에도 공용 카테고리를 선택할 수 있다', () => {
  assert.deepEqual(categoriesForUpload([personal], 'PRIVATE', true, ['공식 문서']), ['공식 문서'])
})

test('목록 카테고리는 현재 범위의 문서로만 만든다', () => {
  assert.deepEqual(categoryNames(documentsForScope([shared, personal], 'SHARED')), ['공식 문서'])
})
