export type ScopedDocument = { scope: 'SHARED' | 'PRIVATE'; category: string }

export function documentsForScope<T extends ScopedDocument>(documents: T[], scope: ScopedDocument['scope']): T[] {
  return documents.filter(document => document.scope === scope)
}

export function categoryNames(documents: ScopedDocument[]): string[] {
  return [...new Set(documents.map(document => document.category))].sort((a, b) => a.localeCompare(b, 'ko'))
}

export function categoriesForUpload(documents: ScopedDocument[], scope: ScopedDocument['scope'], admin: boolean,
                                    sharedCategories: string[]): string[] {
  return admin && scope === 'PRIVATE' ? sharedCategories : categoryNames(documents)
}
