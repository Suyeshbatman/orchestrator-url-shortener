package com.acme.shortener.link;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;
import org.springframework.data.repository.query.Param;

public interface LinkRepository extends ListCrudRepository<Link, Long> {

  Optional<Link> findByCode(String code);

  /** Case-insensitive existence check across generated codes and aliases (alias validation). */
  @Query("SELECT EXISTS (SELECT 1 FROM link WHERE lower(code) = lower(:code))")
  boolean existsByCodeIgnoreCase(@Param("code") String code);

  @Query("SELECT nextval('link_id_seq')")
  long nextId();
}
