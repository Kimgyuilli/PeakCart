package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.AddCartItemCommand;
import com.peekcart.order.application.dto.CartDetailDto;
import com.peekcart.order.application.dto.UpdateCartItemCommand;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.repository.CartRepository;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장바구니 수정을 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class CartCommandService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    /**
     * 장바구니에 상품을 추가한다. 장바구니가 없으면 자동으로 생성한다.
     *
     * @throws ProductException 상품이 존재하지 않으면 {@code PRD-001}
     */
    public CartDetailDto addItem(Long userId, AddCartItemCommand command) {
        productRepository.findById(command.productId())
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));

        Cart cart = cartRepository.findByUserId(userId)
                .orElseGet(() -> cartRepository.save(Cart.create(userId)));
        cart.addItem(command.productId(), command.quantity());
        return CartDetailDto.from(cart);
    }

    /**
     * 장바구니 항목의 수량을 변경한다.
     *
     * @throws OrderException 장바구니가 없으면 {@code ORD-006}
     */
    public CartDetailDto updateItem(Long userId, Long cartItemId, UpdateCartItemCommand command) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_006));
        cart.updateItemQuantity(cartItemId, command.quantity());
        return CartDetailDto.from(cart);
    }

    /**
     * 장바구니 항목을 삭제한다.
     *
     * @throws OrderException 장바구니가 없으면 {@code ORD-006}
     */
    public void removeItem(Long userId, Long cartItemId) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_006));
        cart.removeItem(cartItemId);
    }
}
