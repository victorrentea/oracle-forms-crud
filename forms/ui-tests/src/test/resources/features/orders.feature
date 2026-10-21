Feature: Orders screen
  As a sales clerk I key orders into the Orders form of the Oracle Forms application.
  The form computes totals and discounts, enforces each customer's credit limit,
  and lets an order move only NEW -> SHIPPED or NEW -> CANCELLED.

  Background:
    Given the Orders form is open

  Scenario: The form opens with the orders already queried
    Then I see these orders:
      | Id | Customer  | Product  | Qty | Total | Status    |
      | 1  | Acme Corp | Widget   | 5   | 100   | NEW       |
      | 2  | Acme Corp | Gadget   | 12  | 570   | SHIPPED   |
      | 3  | Globex    | Sprocket | 3   | 300   | CANCELLED |

  Scenario Outline: Ordering more items earns a volume discount
    When I enter a new order for customer 1: <qty> x "Bolt" at 10
    And I save
    Then the message line says "FRM-40400: Transaction complete: 1 records applied and saved."
    And order line 4 shows a discount of <discount>% and a total of <total>

    Examples:
      | qty | discount | total |
      | 9   | 0        | 90    |
      | 10  | 5        | 95    |
      | 50  | 10       | 450   |

  Scenario: An order over the customer's credit limit is refused
    When I enter a new order for customer 2: 1 x "Gold" at 5000
    And I save
    Then the message line contains "ORA-20002: Credit limit exceeded"

  Scenario: A new order can be shipped
    When I ship the order on line 1
    Then order line 1 has status "SHIPPED"

  Scenario: A shipped order cannot be cancelled
    When I cancel the order on line 2
    Then the message line contains "ORA-20004: Only NEW orders can be cancelled (order is SHIPPED)"
    And order line 2 has status "SHIPPED"
