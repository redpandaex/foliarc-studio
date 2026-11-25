package studio.foliarc.biz.genre;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import studio.foliarc.biz.genre.GenreDtos.GenreProfileResponse;

@RestController
@RequestMapping("/api/v1/genres")
public class GenreController {

    private final GenreProfileRegistry profiles;

    public GenreController(GenreProfileRegistry profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    public List<GenreProfileResponse> listGenres() {
        return profiles.listAll();
    }

    @GetMapping("/{genreCode}")
    public GenreProfileResponse getGenre(@PathVariable String genreCode) {
        return profiles.getRequired(genreCode);
    }
}
