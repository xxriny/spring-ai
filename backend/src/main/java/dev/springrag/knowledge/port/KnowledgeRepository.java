package dev.springrag.knowledge.port;

import dev.springrag.knowledge.dto.DocumentDto;
import java.util.List;
import java.util.UUID;

/** 문서 카탈로그와 청크 관계의 영속화 계약이다. */
public interface KnowledgeRepository {
    /** 기존 URL 문서의 동일성·내용 확인에 필요한 값이다. */
    record Existing(UUID id, String hash, String category) {}

    /**
     * 계정이 볼 수 있는 문서 목록을 조회한다.
     * @param owner 인증 계정
     * @param scope 문서 범위
     * @return 접근 가능한 문서 목록
     */
    List<DocumentDto> list(String owner, String scope);

    /**
     * URL 재수집 시 비교할 기존 문서를 찾는다.
     * @param scope 문서 범위
     * @param url 원본 URL
     * @return 기존 문서 또는 null
     */
    Existing findUrl(String scope, String url);

    /**
     * 문서와 연결된 벡터 청크 식별자를 조회한다.
     * @param id 문서 ID
     * @return 연결된 벡터 청크 ID 목록
     */
    List<String> chunkIds(UUID id);

    /**
     * 문서 카탈로그와 새 청크 관계를 기록한다.
     * @param id 문서 ID
     * @param existing 기존 문서 갱신 여부
     * @param scope 문서 범위
     * @param owner 개인 문서 소유자
     * @param title 문서 제목
     * @param url 원본 URL
     * @param path 저장된 파일 경로
     * @param hash 원본 해시
     * @param category 카테고리
     * @param chunkIds 새 벡터 청크 ID
     */
    void save(UUID id, boolean existing, String scope, String owner, String title, String url,
              String path, String hash, String category, List<String> chunkIds);

    /**
     * 문서 삭제 전 소유권을 확인한다.
     * @param id 문서 ID
     * @param owner 인증 계정
     * @param admin 관리자 여부
     * @return 삭제 권한이 있으면 true
     */
    boolean canDelete(UUID id, String owner, boolean admin);

    /**
     * 문서 카탈로그 항목을 삭제한다.
     * @param id 문서 ID
     */
    void delete(UUID id);
}
