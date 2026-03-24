package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.ProductRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ServiceTest
@DisplayName("ProductQueryService 단위 테스트")
class ProductQueryServiceTest {

    @InjectMocks ProductQueryService productQueryService;

    @Mock ProductRepository productRepository;
    @Mock InventoryRepository inventoryRepository;

    private final Category category = ProductFixture.categoryWithId();
    private final Pageable pageable = PageRequest.of(0, 10);

    // ── getProducts ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProducts: categoryId가 null이면 전체 ON_SALE 상품을 조회한다")
    void getProducts_withoutCategory_callsFindByStatus() {
        Product product = ProductFixture.productWithId(category);
        given(productRepository.findByStatus(ProductStatus.ON_SALE, pageable))
                .willReturn(new PageImpl<>(List.of(product)));

        Page<Product> result = productQueryService.getProducts(null, pageable);

        assertThat(result).hasSize(1);
        then(productRepository).should().findByStatus(ProductStatus.ON_SALE, pageable);
    }

    @Test
    @DisplayName("getProducts: categoryId가 있으면 카테고리 필터로 조회한다")
    void getProducts_withCategory_callsFindByCategoryIdAndStatus() {
        Product product = ProductFixture.productWithId(category);
        given(productRepository.findByCategoryIdAndStatus(
                ProductFixture.DEFAULT_CATEGORY_ID, ProductStatus.ON_SALE, pageable))
                .willReturn(new PageImpl<>(List.of(product)));

        Page<Product> result = productQueryService.getProducts(ProductFixture.DEFAULT_CATEGORY_ID, pageable);

        assertThat(result).hasSize(1);
        then(productRepository).should()
                .findByCategoryIdAndStatus(ProductFixture.DEFAULT_CATEGORY_ID, ProductStatus.ON_SALE, pageable);
    }

    // ── getProduct ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProduct: 상품과 재고가 있으면 DTO를 반환한다")
    void getProduct_success_returnsDto() {
        Product product = ProductFixture.productWithId(category);
        Inventory inventory = ProductFixture.inventoryWithId(product);
        given(productRepository.findById(ProductFixture.DEFAULT_PRODUCT_ID)).willReturn(Optional.of(product));
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.of(inventory));

        ProductDetailDto result = productQueryService.getProduct(ProductFixture.DEFAULT_PRODUCT_ID);

        assertThat(result.id()).isEqualTo(ProductFixture.DEFAULT_PRODUCT_ID);
        assertThat(result.name()).isEqualTo(ProductFixture.DEFAULT_PRODUCT_NAME);
        assertThat(result.stock()).isEqualTo(ProductFixture.DEFAULT_STOCK);
    }

    @Test
    @DisplayName("getProduct: 재고 정보가 없으면 stock=0을 반환한다")
    void getProduct_noInventory_returnsZeroStock() {
        Product product = ProductFixture.productWithId(category);
        given(productRepository.findById(ProductFixture.DEFAULT_PRODUCT_ID)).willReturn(Optional.of(product));
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.empty());

        ProductDetailDto result = productQueryService.getProduct(ProductFixture.DEFAULT_PRODUCT_ID);

        assertThat(result.stock()).isZero();
    }

    @Test
    @DisplayName("getProduct: 상품이 없으면 PRD-001 예외가 발생한다")
    void getProduct_notFound_throwsPRD001() {
        given(productRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productQueryService.getProduct(99L))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_001);
    }
}
