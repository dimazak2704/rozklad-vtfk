package ua.vtc.rozklad.notification.binding;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ChatBindingRepository extends JpaRepository<ChatBinding, UUID> {

    Optional<ChatBinding> findByChatIdAndType(long chatId, BindingType type);

    boolean existsByChatId(long chatId);

    List<ChatBinding> findByType(BindingType type);

    List<ChatBinding> findByTypeAndGroupIdIn(BindingType type, Collection<UUID> groupIds);

    List<ChatBinding> findByTypeAndTeacherIdIn(BindingType type, Collection<UUID> teacherIds);

    Optional<ChatBinding> findByTypeAndTeacherId(BindingType type, UUID teacherId);

    List<ChatBinding> findByTypeInAndDigestEnabledTrue(Collection<BindingType> types);

    @Query("select b from ChatBinding b order by b.type, b.createdAt desc")
    List<ChatBinding> findAllForListing();

    /** Bulk delete runs immediately (a plain delete would be flushed after the following insert). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatBinding b where b.type = :type and b.teacherId = :teacherId and b.chatId <> :chatId")
    int deleteTeacherBindingsElsewhere(BindingType type, UUID teacherId, long chatId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ChatBinding b where b.chatId = :chatId")
    int deleteByChatId(long chatId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ChatBinding b set b.chatId = :toChatId where b.chatId = :fromChatId")
    int migrateChat(long fromChatId, long toChatId);
}
