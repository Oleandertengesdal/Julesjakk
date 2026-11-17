package no.sjakkportal.user;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import no.sjakkportal.common.error.ChessException;
import no.sjakkportal.common.error.ErrorCode;
import no.sjakkportal.rating.EloCalculator;
import no.sjakkportal.rating.TimeCategory;
import no.sjakkportal.user.UserView.RatingView;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final JdbcClient jdbc;

    public UserService(UserRepository users, JdbcClient jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public UserView view(long id) {
        AppUser user = users.findById(id).orElseThrow(() -> new ChessException(ErrorCode.USER_NOT_FOUND));
        return toView(user, ratings(List.of(id)).getOrDefault(id, Map.of()));
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        List<AppUser> all = users.findAll(Sort.by("displayName"));
        Map<Long, Map<TimeCategory, RatingView>> ratings = ratings(all.stream().map(AppUser::getId).toList());
        return all.stream().filter(AppUser::isEnabled).map(user -> toView(user, ratings.getOrDefault(user.getId(), Map.of()))).toList();
    }

    @Transactional
    public UserView linkAccounts(long userId, String lichess, String chessCom) {
        try {
            if (lichess != null) {
                jdbc.sql("UPDATE app_user SET lichess_username = ?, version = version + 1, updated_at = now() WHERE id = ?")
                        .params(lichess.isBlank() ? null : lichess, userId).update();
            }
            if (chessCom != null) {
                jdbc.sql("UPDATE app_user SET chess_com_username = ?, version = version + 1, updated_at = now() WHERE id = ?")
                        .params(chessCom.isBlank() ? null : chessCom, userId).update();
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ChessException(ErrorCode.AUTH_USERNAME_TAKEN, "Denne kontoen er allerede koblet til et annet familiemedlem.");
        }
        return view(userId);
    }

    public UserView toView(AppUser user) {
        return toView(user, ratings(List.of(user.getId())).getOrDefault(user.getId(), Map.of()));
    }

    private Map<Long, Map<TimeCategory, RatingView>> ratings(List<Long> userIds) {
        Map<Long, Map<TimeCategory, RatingView>> result = new HashMap<>();
        if (userIds.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT user_id, category, rating, rated_games FROM player_rating WHERE user_id IN (:ids)")
                .param("ids", userIds)
                .query((rs, row) -> {
                    result.computeIfAbsent(rs.getLong("user_id"), k -> new EnumMap<>(TimeCategory.class))
                            .put(TimeCategory.valueOf(rs.getString("category")),
                                    new RatingView(rs.getInt("rating"), rs.getInt("rated_games")));
                    return null;
                }).list();
        return result;
    }

    private static UserView toView(AppUser user, Map<TimeCategory, RatingView> stored) {
        // Every category is always present, so clients never have to special-case "no games yet".
        Map<String, RatingView> ratings = new LinkedHashMap<>();
        for (TimeCategory category : TimeCategory.values()) {
            ratings.put(category.name(), stored.getOrDefault(category, new RatingView(EloCalculator.INITIAL_RATING, 0)));
        }
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name(), user.isManaged(),
                user.isMustChangePassword(), user.getChessComUsername(), user.getLichessUsername(), ratings);
    }
}
